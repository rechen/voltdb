/* This file is part of VoltDB.
 * Copyright (C) 2008-2013 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.plannodes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import org.json_voltpatches.JSONException;
import org.json_voltpatches.JSONObject;
import org.json_voltpatches.JSONStringer;
import org.voltdb.VoltType;
import org.voltdb.catalog.CatalogMap;
import org.voltdb.catalog.Column;
import org.voltdb.catalog.Database;
import org.voltdb.expressions.AbstractExpression;
import org.voltdb.expressions.ExpressionUtil;
import org.voltdb.expressions.SubqueryExpression;
import org.voltdb.expressions.TupleValueExpression;
import org.voltdb.planner.CompiledPlan;
import org.voltdb.types.ExpressionType;
import org.voltdb.types.PlanNodeType;
import org.voltdb.utils.CatalogUtil;

public abstract class AbstractScanPlanNode extends AbstractPlanNode {

    public enum Members {
        PREDICATE,
        TARGET_TABLE_NAME,
        TARGET_TABLE_ALIAS,
        SUBQUERY_INDICATOR;
    }

    // Store the columns from the table as an internal NodeSchema
    // for consistency of interface
    protected NodeSchema m_tableSchema = null;
    // Store the columns we use from this table as an internal schema
    protected NodeSchema m_tableScanSchema = new NodeSchema();
    protected AbstractExpression m_predicate;

    // The target table is the table that the plannode wants to perform some operation on.
    protected String m_targetTableName = "";
    protected String m_targetTableAlias = null;

    // Flag marking the sub-query plan
    protected boolean m_isSubQuery = false;

    protected AbstractScanPlanNode() {
        super();
    }


    protected AbstractScanPlanNode(String tableName, String tableAlias) {
        super();
        m_targetTableName = tableName;
        m_targetTableAlias = tableAlias;
    }

    @Override
    public void getTablesAndIndexes(Collection<String> tablesRead,
                                    Collection<String> tableAliasesRead,
                                    Collection<String> tableUpdated,
                                    Collection<String> tableAliaseUpdated,
                                    Collection<String> indexes)
    {
        assert(m_targetTableName != null);
        assert(m_targetTableAlias != null);
        tableAliasesRead.add(m_targetTableAlias);
        if (!m_isSubQuery) {
            tablesRead.add(m_targetTableName);
        } else {
            assert(m_children.size() == 1);
            m_children.get(0).getTablesAndIndexes(tablesRead, tableAliasesRead, tableUpdated, tableAliaseUpdated, indexes);
        }
    }

    @Override
    public void validate() throws Exception {
        super.validate();
        //
        // TargetTableId
        //
        if (m_targetTableName == null) {
            throw new Exception("ERROR: TargetTableName is null for PlanNode '" + toString() + "'");
        }
        if (m_targetTableAlias == null) {
            throw new Exception("ERROR: TargetTableAlias is null for PlanNode '" + toString() + "'");
        }
        //
        // Filter Expression
        // It is allowed to be null, but we need to check that it's valid
        //
        if (m_predicate != null) {
            m_predicate.validate();
        }
        // All the schema columns better reference this table
        for (SchemaColumn col : m_tableScanSchema.getColumns())
        {
            if (!m_targetTableName.equals(col.getTableName()))
            {
                throw new Exception("ERROR: The scan column: " + col.getColumnName() +
                                    " in table: " + m_targetTableName + " refers to " +
                                    " table: " + col.getTableName());
            }
        }
    }

    /**
     * Does the plan guarantee an identical result/effect
     * when "replayed" against the same database state, such as during replication or CL recovery.
     * @return true unless the scan has an inline limit and no particular order.
     */
    @Override
    public boolean isContentDeterministic() {
        AbstractPlanNode limit = this.getInlinePlanNode(PlanNodeType.LIMIT);
        if ((limit == null) || isOrderDeterministic()) {
            return true;
        } else {
            m_nondeterminismDetail = "a limit on an unordered scan may return different rows";
            return false;
        }
    }

    @Override
    public int overrideId(int newId) {
        m_id = newId++;
        // Now override the ids in the subqueries nodes if any
        if (m_predicate != null) {
            List<AbstractExpression> subqueries = m_predicate.findAllSubexpressionsOfType(ExpressionType.SUBQUERY);
            for (AbstractExpression subquery : subqueries) {
                assert(subquery instanceof SubqueryExpression);
                CompiledPlan subqueryPlan = ((SubqueryExpression)subquery).getTable().getBetsCostPlan();
                newId = subqueryPlan.resetPlanNodeIds(newId);
            }
        }
        return newId;
    }

    /**
     * @return the target_table_name
     */
    public String getTargetTableName() {
        assert(m_targetTableName != null);
        return m_targetTableName;
    }

    /**
     * @param name
     */
    public void setTargetTableName(String name) {
        assert(name != null);
        m_targetTableName = name;
    }

    /**
     * @return the target_table_alias
     */
    public String getTargetTableAlias() {
        assert(m_targetTableAlias != null);
        return m_targetTableAlias;
    }

    /**
     * @param alias
     */
    public void setTargetTableAlias(String alias) {
        assert(alias != null);
        m_targetTableAlias = alias;
    }

    /**
     * @return the predicate
     */
    public AbstractExpression getPredicate() {
        return m_predicate;
    }

    /**
     * @param predicate the predicate to set
     *
     */
    public void setPredicate(AbstractExpression predicate) {
        if (predicate != null)
        {
            // PlanNodes all need private deep copies of expressions
            // so that the resolveColumnIndexes results
            // don't get bashed by other nodes or subsequent planner runs
            m_predicate = (AbstractExpression) predicate.clone();
        } else {
            m_predicate = null;
        }
    }

    public void setScanColumns(Collection<SchemaColumn> scanColumns)
    {
        if (scanColumns != null)
        {
            for (SchemaColumn col : scanColumns)
            {
                m_tableScanSchema.addColumn(col.clone());
            }
        }
    }

    NodeSchema getTableSchema()
    {
        return m_tableSchema;
    }

    /**
     * Set the sub-query flag
     * @param isSubQuery
     */

    public void setSubQuery(boolean isSubQuery) {
        m_isSubQuery = isSubQuery;
    }

    /**
     * Accessor to return the sub-query flag
     * @return m_isSubQuery
     */
    public boolean isSubQuery() {
        return m_isSubQuery;
    }

    @Override
    public void generateOutputSchema(Database db)
    {
        // fill in the table schema if we haven't already
        if (m_tableSchema == null)
        {
            generateTableSchema(db);
        }

        // Until the scan has an implicit projection rather than an explicitly
        // inlined one, the output schema generation is going to be a bit odd.
        // It will depend on two bits of state: whether any scan columns were
        // specified for this table and whether or not there is an inlined
        // projection.
        //
        // If there is an inlined projection, then we'll just steal that
        // output schema as our own.
        // If there is no inlined projection, then, if there are no scan columns
        // specified, use the entire table's schema as the output schema.
        // Otherwise add an inline projection that projects the scan columns
        // and then take that output schema as our own.
        // These have the effect of repeatably generating the correct output
        // schema if called again and again, but also allowing the planner
        // to overwrite the inline projection and still have the right thing
        // happen
        ProjectionPlanNode proj =
            (ProjectionPlanNode)getInlinePlanNode(PlanNodeType.PROJECTION);
        if (proj != null)
        {
            // Does this operation needs to change complex expressions
            // into tuple value expressions with an column alias?
            // Is this always true for clone?  Or do we need a new method?
            m_outputSchema = proj.getOutputSchema().copyAndReplaceWithTVE();
            m_hasSignificantOutputSchema = false; // It's just a cheap knock-off of the projection's
        }
        else
        {
            if (m_tableScanSchema.size() != 0)
            {
                // Order the scan columns according to the table schema
                // before we stick them in the projection output
                List<TupleValueExpression> scan_tves =
                    new ArrayList<TupleValueExpression>();
                for (SchemaColumn col : m_tableScanSchema.getColumns())
                {
                    assert(col.getExpression() instanceof TupleValueExpression);
                    scan_tves.addAll(ExpressionUtil.getTupleValueExpressions(col.getExpression()));
                }
                // and update their indexes against the table schema
                for (TupleValueExpression tve : scan_tves)
                {
                    int index = tve.resolveColumnIndexesUsingSchema(m_tableSchema);
                    tve.setColumnIndex(index);
                }
                m_tableScanSchema.sortByTveIndex();
                // Create inline projection to map table outputs to scan outputs
                ProjectionPlanNode map = new ProjectionPlanNode();
                map.setOutputSchema(m_tableScanSchema);
                addInlinePlanNode(map);
                // a bit redundant but logically consistent
                m_outputSchema = map.getOutputSchema().copyAndReplaceWithTVE();
                m_hasSignificantOutputSchema = false; // It's just a cheap knock-off of the projection's
            }
            else
            {
                // just fill m_outputSchema with the table's columns
                m_outputSchema = m_tableSchema.clone();
                m_hasSignificantOutputSchema = true;
            }
        }
    }

    @Override
    public void resolveColumnIndexes()
    {
        // The following applies to both seq and index scan.  Index scan has
        // some additional expressions that need to be handled as well

        // predicate expression
        List<TupleValueExpression> predicate_tves =
            ExpressionUtil.getTupleValueExpressions(m_predicate);
        for (TupleValueExpression tve : predicate_tves)
        {
            int index = tve.resolveColumnIndexesUsingSchema(m_tableSchema);
            tve.setColumnIndex(index);
        }

        // inline projection
        ProjectionPlanNode proj =
            (ProjectionPlanNode)getInlinePlanNode(PlanNodeType.PROJECTION);
        if (proj != null)
        {
            proj.resolveColumnIndexesUsingSchema(m_tableSchema);
            m_outputSchema = proj.getOutputSchema().clone();
            m_hasSignificantOutputSchema = false; // It's just a cheap knock-off of the projection's
        }
        else
        {
            // output columns
            // if there was an inline projection we will have copied these already
            // otherwise we need to iterate through the output schema TVEs
            // and sort them by table schema index order.

            for (SchemaColumn col : m_outputSchema.getColumns())
            {
                // At this point, they'd better all be TVEs.
                assert(col.getExpression() instanceof TupleValueExpression);
                TupleValueExpression tve = (TupleValueExpression)col.getExpression();
                int index = tve.resolveColumnIndexesUsingSchema(m_tableSchema);
                tve.setColumnIndex(index);
            }
            m_outputSchema.sortByTveIndex();
        }

        // The outputschema of an inline limit node is completely irrelevant to the EE except that
        // serialization will complain if it contains expressions of unresolved columns.
        // Logically, the limited scan output has the same schema as the pre-limit scan.
        // It's at least as easy to just re-use the known-good output schema of the scan
        // than it would be to carefully resolve the limit node's current output schema.
        // And this simply works regardless of whether the limit was originally applied or inlined
        // before or after the (possibly inline) projection.
        // There's no need to be concerned about re-adjusting the irrelevant outputschema
        // based on the different schema of the original raw scan and the projection.
        LimitPlanNode limit = (LimitPlanNode)getInlinePlanNode(PlanNodeType.LIMIT);
        if (limit != null)
        {
            limit.m_outputSchema = m_outputSchema.clone();
            limit.m_hasSignificantOutputSchema = false; // It's just another cheap knock-off
        }

    }

    //TODO some members not in here
    @Override
    public void toJSONString(JSONStringer stringer) throws JSONException {
        super.toJSONString(stringer);

        stringer.key(Members.PREDICATE.name());
        stringer.value(m_predicate);
        stringer.key(Members.TARGET_TABLE_NAME.name()).value(m_targetTableName);
        stringer.key(Members.TARGET_TABLE_ALIAS.name()).value(m_targetTableAlias);
        if (m_isSubQuery) {
            stringer.key(Members.SUBQUERY_INDICATOR.name()).value("TRUE");
        }
    }

    @Override
    public void loadFromJSONObject( JSONObject jobj, Database db ) throws JSONException {
        helpLoadFromJSONObject(jobj, db);
        m_predicate = AbstractExpression.fromJSONChild(jobj, Members.PREDICATE.name());
        m_targetTableName = jobj.getString( Members.TARGET_TABLE_NAME.name() );
        m_targetTableAlias = jobj.getString( Members.TARGET_TABLE_ALIAS.name() );
        if (jobj.has("SUBQUERY_INDICATOR")) {
            m_isSubQuery = "TRUE".equalsIgnoreCase(jobj.getString( Members.SUBQUERY_INDICATOR.name() ));
        }
    }

    @Override
    public void getScanNodeList_recurse(ArrayList<AbstractScanPlanNode> collected,
            HashSet<AbstractPlanNode> visited) {
        if (visited.contains(this)) {
            assert(false): "do not expect loops in plangraph.";
            return;
        }
        visited.add(this);
        collected.add(this);
    }

    protected String explainPredicate(String prefix) {
        // TODO Auto-generated method stub
        if (m_predicate != null) {
            return prefix + m_predicate.explain(m_targetTableName);
        }
        return "";
    }

    protected void generateTableSchema(Database db) {
        m_tableSchema = new NodeSchema();
        CatalogMap<Column> cols =
            db.getTables().getIgnoreCase(m_targetTableName).getColumns();
        // you don't strictly need to sort this, but it makes diff-ing easier
        for (Column col : CatalogUtil.getSortedCatalogItems(cols, "index"))
        {
            // must produce a tuple value expression for this column.
            TupleValueExpression tve = new TupleValueExpression(
                    m_targetTableName, m_targetTableAlias, col.getTypeName(), col.getTypeName(), col.getIndex());
            tve.setValueType(VoltType.get((byte)col.getType()));
            tve.setValueSize(col.getSize());
            m_tableSchema.addColumn(new SchemaColumn(m_targetTableName,
                                                     m_targetTableAlias,
                                                     col.getTypeName(),
                                                     col.getTypeName(),
                                                     tve));
        }
    }
}
