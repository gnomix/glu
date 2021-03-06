/*
 * Copyright (c) 2011 Yan Pujante
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.linkedin.glu.orchestration.engine.delta

import org.linkedin.glu.provisioner.core.model.SystemFilter
import org.linkedin.glu.utils.core.Externable
import org.linkedin.glu.provisioner.core.model.SystemFilterBuilder
import org.linkedin.glu.groovy.utils.GluGroovyLangUtils

/**
 * @author yan@pongasoft.com */
public class CustomDeltaDefinition implements Externable
{
  /**
   * The name of this custom delta
   */
  String name

  /**
   * An (optional) description
   */
  String description

  /**
   * It is a list because the order is important! The first column in the list will be used as
   * the 'group by' column
   */
  List<CustomDeltaColumnDefinition> columnsDefinition

  /**
   * This entry represent which rows should be part of the delta by filtering out the system
   */
  SystemFilter customFilter

  boolean errorsOnly = false

  boolean summary = true

  Collection<CustomDeltaColumnDefinition> getVisibleColumns()
  {
    return columnsDefinition.findAll { it.isVisible() }
  }

  void setColumnsDefinition(List<CustomDeltaColumnDefinition> columnsDefinition)
  {
    if(columnsDefinition?.name?.unique()?.size() != columnsDefinition?.size())
    {
      def names = [:]
      columnsDefinition.name.each { name -> names[name] = (names[name] ?: 0) + 1 }
      throw new IllegalArgumentException("duplicate name(s) not allowed ${names.findAll { k,v -> v > 1 }.keySet()}")
    }

    this.columnsDefinition = columnsDefinition
  }

  CustomDeltaColumnDefinition getFirstColumn()
  {
    return visibleColumns[0]
  }

  Collection<CustomDeltaColumnDefinition> getTailColumns()
  {
    return visibleColumns[1..-1]
  }

  Map<String, String> getTailOrderBy()
  {
    Map<String, String> orderBy = [:]
    visibleColumns[1..-1].each { CustomDeltaColumnDefinition cdcd ->
      if(cdcd.orderBy)
        orderBy[cdcd.name] = cdcd.orderBy
    }
    return orderBy
  }

  String getGroupBy()
  {
    firstColumn.groupBy
  }

  CustomDeltaDefinition groupBy(String columnName)
  {
    if(!columnName)
      return this

    CustomDeltaColumnDefinition groupByColumn =
      columnsDefinition?.find {CustomDeltaColumnDefinition cdcd ->
        cdcd.name == columnName
      }

    if(groupByColumn)
    {
      List newColumnsDefinition = columnsDefinition.collect { it }
      newColumnsDefinition.remove(groupByColumn)
      newColumnsDefinition = [groupByColumn, * newColumnsDefinition]
      
      return new CustomDeltaDefinition(name: name,
                                       customFilter: customFilter,
                                       errorsOnly: errorsOnly,
                                       summary: summary,
                                       columnsDefinition: newColumnsDefinition)
    }
    else
      return this
  }

  @Override
  def toExternalRepresentation()
  {
    return [
      name: name,
      description: description,
      customFilter: customFilter?.toDSL(),
      errorsOnly: errorsOnly,
      summary: summary,
      columnsDefinition: columnsDefinition?.collect { it.toExternalRepresentation() }
    ]
  }

  @Override
  CustomDeltaDefinition clone()
  {
    fromExternalRepresentation(toExternalRepresentation())
  }

  static CustomDeltaDefinition fromExternalRepresentation(def er)
  {
    if(er == null)
      return null

    boolean errorsOnly = GluGroovyLangUtils.getOptionalBoolean(er.errorsOnly, false)
    boolean summary = GluGroovyLangUtils.getOptionalBoolean(er.summary, true)

    CustomDeltaDefinition res = new CustomDeltaDefinition(name: er.name,
                                                          description: er.description,
                                                          customFilter: SystemFilterBuilder.parse(er.customFilter),
                                                          errorsOnly: errorsOnly,
                                                          summary: summary)

    res.columnsDefinition = er.columnsDefinition?.collect {
      CustomDeltaColumnDefinition.fromExternalRepresentation(it) } ?: []

    return res
  }

  /**
   * The purpose of this method is to take the 'old' representation of the dashboard and convert it
   * into the new one
   *
   * Here is an example of 'old' dashboard:
   * </pre>
   *   mountPoint: [checked: true, name: 'mountPoint', groupBy: true, linkFilter: true],
   *   agent: [checked: true, name: 'agent', groupBy: true],
   *   'metadata.container.name': [checked: true, name: 'container', groupBy: true, linkFilter: true],
   * </pre>
   * @param dashboard
   * @return
   */
  static CustomDeltaDefinition fromDashboard(def dashboard)
  {
    if(dashboard == null)
      return null

    if(dashboard instanceof String)
      return new CustomDeltaDefinitionSerializerImpl().deserialize(dashboard,
                                                                   CustomDeltaDefinitionSerializerImpl.LATEST_CONTENT_VERSION)

    if(dashboard instanceof Map && dashboard.columnsDefinition instanceof Collection)
      return CustomDeltaDefinition.fromExternalRepresentation(dashboard)

    CustomDeltaDefinition res = new CustomDeltaDefinition(name: "dashboard",
                                                          customFilter: null,
                                                          errorsOnly: false,
                                                          summary: true)

    def columnsDefinition = []

    if(dashboard instanceof Collection)
    {
      dashboard.each { entry ->
        CustomDeltaColumnDefinition cdcd =
          CustomDeltaColumnDefinition.fromExternalRepresentation(entry)
        columnsDefinition << cdcd
      }
    }
    else
    {
      // dashboard is a map where the key is 'source' (old dashboard...)
      dashboard.each { entry ->
        CustomDeltaColumnDefinition cdcd = new CustomDeltaColumnDefinition(source: entry.key)

        // value is a map 'defining' the column
        def c = entry.value

        if(c.checked)
        {
          cdcd.name = c.name
          cdcd.linkable = GluGroovyLangUtils.getOptionalBoolean(c.linkFilter, true)

          if(cdcd.source == 'tags')
            cdcd.groupBy = 'uniqueVals'

          columnsDefinition << cdcd
        }
      }

      // status requires statusInfo to work properly
      if(columnsDefinition.any { it.source == 'status' })
      {
        CustomDeltaColumnDefinition cdcd = new CustomDeltaColumnDefinition(source: 'statusInfo',
                                                                           name: 'statusInfo',
                                                                           groupBy: 'vals',
                                                                           visible: false)

        columnsDefinition << cdcd
      }

      // state is required to display colors properly
      if(!columnsDefinition.any { it.source == 'state' })
      {
        CustomDeltaColumnDefinition cdcd = new CustomDeltaColumnDefinition(source: 'state',
                                                                           name: 'state',
                                                                           visible: false)

        columnsDefinition << cdcd
      }
    }

    res.columnsDefinition = columnsDefinition

    return res
  }
}