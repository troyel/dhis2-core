package org.hisp.dhis.datavalue.hibernate;

/*
 * Copyright (c) 2004-2016, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * Neither the name of the HISP project nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.Criteria;
import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.Projections;
import org.hibernate.criterion.Restrictions;
import org.hisp.dhis.common.BaseDimensionalItemObject;
import org.hisp.dhis.common.IdentifiableObjectUtils;
import org.hisp.dhis.common.MapMap;
import org.hisp.dhis.commons.util.TextUtils;
import org.hisp.dhis.dataelement.CategoryOptionGroup;
import org.hisp.dhis.dataelement.DataElement;
import org.hisp.dhis.dataelement.DataElementCategoryCombo;
import org.hisp.dhis.dataelement.DataElementCategoryOption;
import org.hisp.dhis.dataelement.DataElementCategoryOptionCombo;
import org.hisp.dhis.dataelement.DataElementOperand;
import org.hisp.dhis.datavalue.DataValue;
import org.hisp.dhis.datavalue.DataValueStore;
import org.hisp.dhis.datavalue.DeflatedDataValue;
import org.hisp.dhis.expression.Expression;
import org.hisp.dhis.expression.ExpressionService;
import org.hisp.dhis.organisationunit.OrganisationUnit;
import org.hisp.dhis.period.Period;
import org.hisp.dhis.period.PeriodStore;
import org.hisp.dhis.period.PeriodType;
import org.hisp.dhis.system.objectmapper.DataValueRowMapper;
import org.hisp.dhis.system.objectmapper.DeflatedDataValueRowMapper;
import org.hisp.dhis.system.util.DateUtils;
import org.hisp.dhis.system.util.MathUtils;
import org.hisp.dhis.validation.DeflatedValidationResult;
import org.hisp.dhis.validation.RuleType;
import org.hisp.dhis.validation.ValidationRule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.rowset.SqlRowSet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;

import static org.hisp.dhis.common.IdentifiableObjectUtils.getIdentifiers;
import static org.hisp.dhis.commons.util.TextUtils.getCommaDelimitedString;
import static org.hisp.dhis.expression.ExpressionService.OPERAND_SUM_PATTERN;

/**
 * @author Torgeir Lorange Ostby
 */
public class HibernateDataValueStore
    implements DataValueStore {
    private static final Log log = LogFactory.getLog(HibernateDataValueStore.class);

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private SessionFactory sessionFactory;

    public void setSessionFactory(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    private PeriodStore periodStore;

    public void setPeriodStore(PeriodStore periodStore) {
        this.periodStore = periodStore;
    }

    private JdbcTemplate jdbcTemplate;

    public void setJdbcTemplate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // -------------------------------------------------------------------------
    // Basic DataValue
    // -------------------------------------------------------------------------

    @Override
    public void addDataValue(DataValue dataValue) {
        dataValue.setPeriod(periodStore.reloadForceAddPeriod(dataValue.getPeriod()));

        Session session = sessionFactory.getCurrentSession();

        session.save(dataValue);
    }

    @Override
    public void updateDataValue(DataValue dataValue) {
        dataValue.setPeriod(periodStore.reloadForceAddPeriod(dataValue.getPeriod()));

        Session session = sessionFactory.getCurrentSession();

        session.update(dataValue);
    }

    @Override
    public void deleteDataValue(DataValue dataValue) {
        Session session = sessionFactory.getCurrentSession();

        session.delete(dataValue);
    }

    @Override
    public void deleteDataValues(OrganisationUnit organisationUnit) {
        String hql = "delete from DataValue d where d.source = :source";

        sessionFactory.getCurrentSession().createQuery(hql).
                setEntity("source", organisationUnit).executeUpdate();
    }

    @Override
    public DataValue getDataValue(DataElement dataElement, Period period, OrganisationUnit source,
                                  DataElementCategoryOptionCombo categoryOptionCombo, DataElementCategoryOptionCombo attributeOptionCombo) {
        Session session = sessionFactory.getCurrentSession();

        Period storedPeriod = periodStore.reloadPeriod(period);

        if (storedPeriod == null) {
            return null;
        }

        Criteria criteria = session.createCriteria(DataValue.class);
        criteria.add(Restrictions.eq("source", source));
        criteria.add(Restrictions.eq("dataElement", dataElement));
        criteria.add(Restrictions.eq("period", storedPeriod));
        criteria.add(Restrictions.eq("categoryOptionCombo", categoryOptionCombo));
        criteria.add(Restrictions.eq("attributeOptionCombo", attributeOptionCombo));

        return (DataValue) criteria.uniqueResult();
    }

    @Override
    public DataValue getDataValue(int dataElementId, int periodId, int sourceId, int categoryOptionComboId, int attributeOptionComboId) {
        final String sql =
                "select * from datavalue " +
                        "where dataelementid = " + dataElementId + " " +
                        "and periodid = " + periodId + " " +
                        "and sourceid = " + sourceId + " " +
                        "and categoryoptioncomboid = " + categoryOptionComboId + " " +
                        "and attributeoptioncomboid = " + attributeOptionComboId;

        return jdbcTemplate.queryForObject(sql, new DataValueRowMapper());
    }

    // -------------------------------------------------------------------------
    // Collections of DataValues
    // -------------------------------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public List<DataValue> getAllDataValues() {
        Session session = sessionFactory.getCurrentSession();

        Criteria criteria = session.createCriteria(DataValue.class);

        return criteria.list();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<DataValue> getDataValues(OrganisationUnit source, Period period) {
        Period storedPeriod = periodStore.reloadPeriod(period);

        if (storedPeriod == null) {
            return new ArrayList<>();
        }

        Session session = sessionFactory.getCurrentSession();

        Criteria criteria = session.createCriteria(DataValue.class);
        criteria.add(Restrictions.eq("source", source));
        criteria.add(Restrictions.eq("period", storedPeriod));

        return criteria.list();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<DataValue> getDataValues(OrganisationUnit source, DataElement dataElement) {
        Session session = sessionFactory.getCurrentSession();

        Criteria criteria = session.createCriteria(DataValue.class);
        criteria.add(Restrictions.eq("source", source));
        criteria.add(Restrictions.eq("dataElement", dataElement));

        return criteria.list();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<DataValue> getDataValues(Collection<OrganisationUnit> sources, DataElement dataElement) {
        Session session = sessionFactory.getCurrentSession();

        if (sources == null || sources.isEmpty()) {
            return new ArrayList<>();
        }

        Criteria criteria = session.createCriteria(DataValue.class);
        criteria.add(Restrictions.in("source", sources));
        criteria.add(Restrictions.eq("dataElement", dataElement));

        return criteria.list();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<DataValue> getDataValues(OrganisationUnit source, Period period, Collection<DataElement> dataElements) {
        Period storedPeriod = periodStore.reloadPeriod(period);

        if (storedPeriod == null || dataElements == null || dataElements.isEmpty()) {
            return new ArrayList<>();
        }

        Session session = sessionFactory.getCurrentSession();

        Criteria criteria = session.createCriteria(DataValue.class);
        criteria.add(Restrictions.eq("source", source));
        criteria.add(Restrictions.eq("period", storedPeriod));
        criteria.add(Restrictions.in("dataElement", dataElements));

        return criteria.list();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<DataValue> getDataValues(OrganisationUnit source, Period period,
                                         Collection<DataElement> dataElements, DataElementCategoryOptionCombo attributeOptionCombo) {
        Period storedPeriod = periodStore.reloadPeriod(period);

        if (storedPeriod == null || dataElements == null || dataElements.isEmpty()) {
            return new ArrayList<>();
        }

        Session session = sessionFactory.getCurrentSession();

        Criteria criteria = session.createCriteria(DataValue.class);
        criteria.add(Restrictions.eq("source", source));
        criteria.add(Restrictions.eq("period", storedPeriod));
        criteria.add(Restrictions.in("dataElement", dataElements));
        criteria.add(Restrictions.eq("attributeOptionCombo", attributeOptionCombo));

        return criteria.list();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<DataValue> getDataValues(OrganisationUnit source, Period period, Collection<DataElement> dataElements,
                                         Collection<DataElementCategoryOptionCombo> categoryOptionCombos) {
        Period storedPeriod = periodStore.reloadPeriod(period);

        if (storedPeriod == null || dataElements == null || dataElements.isEmpty() || categoryOptionCombos == null || categoryOptionCombos.isEmpty()) {
            return new ArrayList<>();
        }

        Session session = sessionFactory.getCurrentSession();

        Criteria criteria = session.createCriteria(DataValue.class);
        criteria.add(Restrictions.eq("source", source));
        criteria.add(Restrictions.eq("period", storedPeriod));
        criteria.add(Restrictions.in("dataElement", dataElements));
        criteria.add(Restrictions.in("categoryOptionCombo", categoryOptionCombos));

        return criteria.list();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<DataValue> getDataValues(DataElement dataElement, Period period,
                                         Collection<OrganisationUnit> sources) {
        Period storedPeriod = periodStore.reloadPeriod(period);

        if (storedPeriod == null || sources == null || sources.isEmpty()) {
            return new ArrayList<>();
        }

        Session session = sessionFactory.getCurrentSession();

        Criteria criteria = session.createCriteria(DataValue.class);
        criteria.add(Restrictions.eq("dataElement", dataElement));
        criteria.add(Restrictions.eq("period", storedPeriod));
        criteria.add(Restrictions.in("source", sources));

        return criteria.list();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<DataValue> getDataValues(DataElement dataElement, Collection<Period> periods,
                                         Collection<OrganisationUnit> sources) {
        Collection<Period> storedPeriods = new ArrayList<>();

        for (Period period : periods) {
            Period storedPeriod = periodStore.reloadPeriod(period);

            if (storedPeriod != null) {
                storedPeriods.add(storedPeriod);
            }
        }

        if (storedPeriods.isEmpty() || sources == null || sources.isEmpty()) {
            return new ArrayList<>();
        }

        Session session = sessionFactory.getCurrentSession();

        Criteria criteria = session.createCriteria(DataValue.class);
        criteria.add(Restrictions.eq("dataElement", dataElement));
        criteria.add(Restrictions.in("period", storedPeriods));
        criteria.add(Restrictions.in("source", sources));

        return criteria.list();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<DataValue> getDataValues(DataElement dataElement, DataElementCategoryOptionCombo categoryOptionCombo,
                                         Collection<Period> periods, Collection<OrganisationUnit> sources) {
        Collection<Period> storedPeriods = new ArrayList<>();

        for (Period period : periods) {
            Period storedPeriod = periodStore.reloadPeriod(period);

            if (storedPeriod != null) {
                storedPeriods.add(storedPeriod);
            }
        }

        if (storedPeriods.isEmpty() || sources == null || sources.isEmpty()) {
            return new ArrayList<>();
        }

        Session session = sessionFactory.getCurrentSession();

        Criteria criteria = session.createCriteria(DataValue.class);
        criteria.add(Restrictions.eq("dataElement", dataElement));
        criteria.add(Restrictions.eq("categoryOptionCombo", categoryOptionCombo));
        criteria.add(Restrictions.in("period", storedPeriods));
        criteria.add(Restrictions.in("source", sources));

        return criteria.list();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<DataValue> getDataValues(Collection<DataElementCategoryOptionCombo> categoryOptionCombos) {
        Session session = sessionFactory.getCurrentSession();

        if (categoryOptionCombos == null || categoryOptionCombos.isEmpty()) {
            return new ArrayList<>();
        }

        Criteria criteria = session.createCriteria(DataValue.class);
        criteria.add(Restrictions.in("categoryOptionCombo", categoryOptionCombos));

        return criteria.list();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<DataValue> getDataValues(DataElement dataElement) {
        Session session = sessionFactory.getCurrentSession();

        Criteria criteria = session.createCriteria(DataValue.class);
        criteria.add(Restrictions.eq("dataElement", dataElement));

        return criteria.list();
    }

    @Override
    public List<DeflatedDataValue> getDeflatedDataValues(DataElement dataElement, DataElementCategoryOptionCombo categoryOptionCombo,
                                                         Collection<Period> periods, Collection<OrganisationUnit> sources) {
        List<DeflatedDataValue> result = new ArrayList<DeflatedDataValue>();
        Collection<Integer> periodIdList = IdentifiableObjectUtils.getIdentifiers(periods);
        List<Integer> sourceIdList = IdentifiableObjectUtils.getIdentifiers(sources);
        Integer dataElementId = dataElement.getId();

        String sql = "select categoryoptioncomboid, attributeoptioncomboid, value, sourceid, periodid, storedby, created, lastupdated, comment, followup " +
                "from datavalue where dataelementid=" + dataElementId + " " +
                ((categoryOptionCombo == null) ? "" : ("and categoryoptioncomboid=" + categoryOptionCombo.getId() + " ")) +
                "and sourceid in (" + TextUtils.getCommaDelimitedString(sourceIdList) + ") " +
                "and periodid in (" + TextUtils.getCommaDelimitedString(periodIdList) + ")";

        SqlRowSet rowSet = jdbcTemplate.queryForRowSet(sql);

        while (rowSet.next()) {
            Integer categoryOptionComboId = rowSet.getInt(1);
            Integer attributeOptionComboId = rowSet.getInt(2);
            String value = rowSet.getString(3);
            Integer sourceId = rowSet.getInt(4);
            Integer periodId = rowSet.getInt(5);
            String storedBy = rowSet.getString(6);
            Date created = rowSet.getDate(7);
            Date lastUpdated = rowSet.getDate(8);
            String comment = rowSet.getString(9);
            boolean followup = rowSet.getBoolean(10);

            if (value != null) {
                DeflatedDataValue dv = new DeflatedDataValue(dataElementId, periodId, sourceId,
                        categoryOptionComboId, attributeOptionComboId, value,
                        storedBy, created, lastUpdated,
                        comment, followup);

                result.add(dv);
            }
        }

        return result;
    }

    @Override
    public List<DeflatedDataValue> sumRecursiveDeflatedDataValues(
            DataElement dataElement, DataElementCategoryOptionCombo categoryOptionCombo,
            Collection<Period> periods, OrganisationUnit source) {
        List<DeflatedDataValue> result = new ArrayList<DeflatedDataValue>();
        Collection<Integer> periodIdList = IdentifiableObjectUtils.getIdentifiers(periods);
        Integer dataElementId = dataElement.getId();
        String sourcePrefix = source.getPath();
        Integer sourceId = source.getId();

        String castType = "double precision"; //TODO use statement builder

        String sql = "SELECT dataelementid, categoryoptioncomboid, attributeoptioncomboid, periodid, " +
                "SUM(CAST(value AS " + castType + ")) AS value " +
                "FROM datavalue, organisationunit " +
                "WHERE dataelementid=" + dataElementId + " " +
                "AND sourceid = organisationunitid " +
                ((categoryOptionCombo == null) ? "" :
                        ("and categoryoptioncomboid=" + categoryOptionCombo.getId() + " ")) +
                "AND path LIKE '" + sourcePrefix + "%' " +
                "AND periodid IN (" + TextUtils.getCommaDelimitedString(periodIdList) + ") " +
                "GROUP BY dataelementid, categoryoptioncomboid, attributeoptioncomboid, periodid";

        SqlRowSet rowSet = jdbcTemplate.queryForRowSet(sql);

        while (rowSet.next()) {
            Integer categoryOptionComboId = rowSet.getInt(2);
            Integer attributeOptionComboId = rowSet.getInt(3);
            Integer periodId = rowSet.getInt(4);
            String value = rowSet.getString(5);

            if (value != null) {
                DeflatedDataValue dv = new DeflatedDataValue(dataElementId, periodId, sourceId,
                        categoryOptionComboId, attributeOptionComboId, value);

                result.add(dv);
            }
        }

        return result;
    }

    @Override
    public DataValue getLatestDataValues(DataElement dataElement, PeriodType periodType,
                                         OrganisationUnit organisationUnit) {
        final String hsql = "SELECT v FROM DataValue v, Period p WHERE  v.dataElement =:dataElement "
                + " AND v.period=p AND p.periodType=:periodType AND v.source=:source ORDER BY p.endDate DESC";

        Session session = sessionFactory.getCurrentSession();

        Query query = session.createQuery(hsql);

        query.setParameter("dataElement", dataElement);
        query.setParameter("periodType", periodType);
        query.setParameter("source", organisationUnit);

        query.setFirstResult(0);
        query.setMaxResults(1);

        return (DataValue) query.uniqueResult();
    }

    @Override
    public int getDataValueCountLastUpdatedAfter(Date date) {
        Criteria criteria = sessionFactory.getCurrentSession().createCriteria(DataValue.class);

        criteria.add(Restrictions.ge("lastUpdated", date));
        criteria.setProjection(Projections.rowCount());

        Number rs = (Number) criteria.uniqueResult();

        return rs != null ? rs.intValue() : 0;
    }

    @Override
    public MapMap<Integer, DataElementOperand, Double> getDataValueMapByAttributeCombo(Collection<DataElement> dataElements, Date date,
                                                                                       OrganisationUnit source, Collection<PeriodType> periodTypes, DataElementCategoryOptionCombo attributeCombo,
                                                                                       Set<CategoryOptionGroup> cogDimensionConstraints, Set<DataElementCategoryOption> coDimensionConstraints,
                                                                                       MapMap<Integer, DataElementOperand, Date> lastUpdatedMap) {
        MapMap<Integer, DataElementOperand, Double> map = new MapMap<>();

        if (dataElements.isEmpty() || periodTypes.isEmpty()
                || (cogDimensionConstraints != null && cogDimensionConstraints.isEmpty())
                || (coDimensionConstraints != null && coDimensionConstraints.isEmpty())) {
            return map;
        }

        String joinCo = coDimensionConstraints == null && cogDimensionConstraints == null ? "" :
                "join categoryoptioncombos_categoryoptions c_c on dv.attributeoptioncomboid = c_c.categoryoptioncomboid ";

        String joinCog = cogDimensionConstraints == null ? "" :
                "join categoryoptiongroupmembers cogm on c_c.categoryoptionid = cogm.categoryoptionid ";

        String whereCo = coDimensionConstraints == null ? "" :
                "and c_c.categoryoptionid in (" + TextUtils.getCommaDelimitedString(getIdentifiers(coDimensionConstraints)) + ") ";

        String whereCog = cogDimensionConstraints == null ? "" :
                "and cogm.categoryoptiongroupid in (" + TextUtils.getCommaDelimitedString(getIdentifiers(cogDimensionConstraints)) + ") ";

        String whereCombo = attributeCombo == null ? "" :
                "and dv.attributeoptioncomboid = " + attributeCombo.getId() + " ";

        String sql = "select de.uid, coc.uid, dv.attributeoptioncomboid, dv.value, dv.lastupdated, p.startdate, p.enddate " +
                "from datavalue dv " +
                "join dataelement de on dv.dataelementid = de.dataelementid " +
                "join categoryoptioncombo coc on dv.categoryoptioncomboid = coc.categoryoptioncomboid " +
                "join period p on p.periodid = dv.periodid " + joinCo + joinCog +
                "where dv.dataelementid in (" + TextUtils.getCommaDelimitedString(getIdentifiers(dataElements)) + ") " +
                "and dv.sourceid = " + source.getId() + " " +
                "and p.startdate <= '" + DateUtils.getMediumDateString(date) + "' " +
                "and p.enddate >= '" + DateUtils.getMediumDateString(date) + "' " +
                "and p.periodtypeid in (" + TextUtils.getCommaDelimitedString(getIds(periodTypes)) + ") " +
                whereCo + whereCog + whereCombo;

        SqlRowSet rowSet = jdbcTemplate.queryForRowSet(sql);

        MapMap<Integer, DataElementOperand, Long> checkForDuplicates = new MapMap<>();

        while (rowSet.next()) {
            String dataElement = rowSet.getString(1);
            String categoryOptionCombo = rowSet.getString(2);
            Integer attributeOptionComboId = rowSet.getInt(3);
            Double value = MathUtils.parseDouble(rowSet.getString(4));
            Date lastUpdated = rowSet.getDate(5);
            Date periodStartDate = rowSet.getDate(6);
            Date periodEndDate = rowSet.getDate(7);
            long periodInterval = periodEndDate.getTime() - periodStartDate.getTime();

            log.trace("row: " + dataElement + " = " + value + " [" + periodStartDate + " : " + periodEndDate + "]");

            if (value != null) {
                DataElementOperand dataElementOperand = new DataElementOperand(dataElement, categoryOptionCombo);

                Long existingPeriodInterval = checkForDuplicates.getValue(attributeOptionComboId, dataElementOperand);

                if (existingPeriodInterval != null && existingPeriodInterval < periodInterval) {
                    // Don't overwrite the previous value if for a shorter interval
                    continue;
                }
                map.putEntry(attributeOptionComboId, dataElementOperand, value);

                if (lastUpdatedMap != null) {
                    lastUpdatedMap.putEntry(attributeOptionComboId, dataElementOperand, lastUpdated);
                }

                checkForDuplicates.putEntry(attributeOptionComboId, dataElementOperand, periodInterval);
            }
        }

        return map;
    }

    @Override
    public Collection<DeflatedDataValue> getDeflatedDataValues(int dataElementId, int periodId, Collection<Integer> sourceIds) {
        final String sql =
                "SELECT * FROM datavalue " +
                        "WHERE dataelementid = " + dataElementId + " " +
                        "AND periodid = " + periodId + " " +
                        "AND sourceid IN ( " + getCommaDelimitedString(sourceIds) + " )";

        return jdbcTemplate.query(sql, new DeflatedDataValueRowMapper());
    }

    private List<Integer> getIds(Collection<PeriodType> periodTypes) {
        List<Integer> ids = new ArrayList<>();

        for (PeriodType pt : periodTypes) {
            ids.add(pt.getId());
        }

        return ids;
    }

    /* Getting SQL for validation rules */

    public String getValidationQuery(ValidationRule rule,
                                     String left_expression, String right_expression,
                                     Set<BaseDimensionalItemObject> left_inputs,
                                     Set<BaseDimensionalItemObject> right_inputs,
                                     Collection<Period> periods,
                                     Collection<OrganisationUnit> sources,
                                     Set<CategoryOptionGroup> cogDimensionConstraints,
                                     Set<DataElementCategoryOption> coDimensionConstraints,
                                     DataElementCategoryCombo default_category_combo) {
        if (rule.getRuleType() == RuleType.VALIDATION) {
            if (OPERAND_SUM_PATTERN.matcher( left_expression ).matches() &&
                    OPERAND_SUM_PATTERN.matcher( left_expression ).matches() )
            {
                String left_sql = getDataInputExpression(left_inputs, default_category_combo );
                String right_sql = getDataInputExpression(right_inputs, default_category_combo );
                Double left_constant = getLiteralConstant( left_expression );
                Double right_constant = getLiteralConstant( right_expression );
                String comparator = getComparator(rule);

                if ((left_sql == null) || (right_sql == null) || (comparator == null))
                    return null;
                else return "SELECT " + rule.getId() + " AS ruleid," +
                        "periodid, sourceid, attributeoptioncomboid, " +
                        left_sql + ("+"+left_constant) + " as left_side, " +
                        right_sql + ("+"+left_constant) + " as right_side " +
                        " from datavalue " +
                        " where dataelementid in " +
                        "(" + elementIds(left_inputs) +
                        (((left_inputs.size() > 0) && (right_inputs.size() > 0)) ? (",") : ("")) +
                        elementIds(right_inputs) + ") " +
                        getSourcesClause(sources) + getPeriodsClause(periods) +
                        " group by periodid, sourceid, attributeoptioncomboid " +
                        " having " + left_sql + " " + comparator + " " + right_sql + " ";
            }
        }

        return null;
    }

    private String elementIds(Set<BaseDimensionalItemObject> inputs) {
        boolean first = true;
        String result = "";
        for (BaseDimensionalItemObject input : inputs) {
            if (input instanceof DataElement) {
                DataElement de = (DataElement) input;
                result = result + ((first) ? ("") : (", ")) + de.getId();
            } else if (input instanceof DataElementOperand) {
                DataElementOperand deo = (DataElementOperand) input;
                result = result + ((first) ? ("") : (", ")) + deo.getId();
            } else {
                continue;
            }
            first = false;
        }
        return result;
    }

    private String getDataInputExpression
            (Set<BaseDimensionalItemObject> inputs, DataElementCategoryCombo default_category_combo) {
        String clause = "sum(case when (";
        boolean first = true; int n_subclauses=0;
        for (BaseDimensionalItemObject input : inputs) {
            if (input instanceof DataElement) {
                DataElement de = (DataElement) input;
                DataElementCategoryCombo cc = de.getCategoryCombo();
                if ((cc == null) || (cc == default_category_combo)) {
                    clause = clause + ((first) ? ("") : (" OR")) +
                            " (dataelementid = " + de.getId() + ") ";
                } else {
                    clause = clause + ((first) ? ("") : (" OR")) +
                            " (dataelementid = " + de.getId() +
                            " AND categoryoptioncomboid = " + cc.getId() + ") ";
                }
            } else if (input instanceof DataElementOperand) {
                DataElementOperand deo = (DataElementOperand) input;
                DataElement de = deo.getDataElement();
                DataElementCategoryOptionCombo cc = deo.getCategoryOptionCombo();
                clause = clause + ((first) ? ("") : (" OR")) +
                        " (dataelementid = " + de.getId() +
                        " AND categoryoptioncomboid = " + cc.getId() + ") ";
            } else {
                return null;
            }
            first = false;
            n_subclauses++;
        }

        if ( n_subclauses > 0)
        {
            return clause + ")" + " then cast(value as double precision) else null end)";
        }
        else return "0";
    }

    private static Double getLiteralConstant(String expression)
    {
        final Matcher matcher = ExpressionService.LITERAL_NUMBER_PATTERN.matcher(expression);
        Double sum=0.0;

        while(matcher.find())
        {
            String digits = matcher.group();
            Double d = Double.valueOf( digits );
            sum=sum+d;
        }

        return sum;
    }


    private static String getComparator( ValidationRule rule )
    {
        // We reverse the sense of the operator since we're looking for validations
        switch ( rule.getOperator() )
        {
            case equal_to:
                return " != ";
            case greater_than:
                return " <= ";
            case greater_than_or_equal_to:
                return " < ";
            case less_than:
                return " >= ";
            case less_than_or_equal_to:
                return " > ";
            case not_equal_to:
                return " = ";
            default:
                return null;
        }
    }

    private static BaseDimensionalItemObject getOne( Set<BaseDimensionalItemObject> set )
    {
        for ( BaseDimensionalItemObject bdio: set )
        {
            return bdio;
        }

        return null;
    }

    private static String getSourcesClause( Collection<OrganisationUnit> sources )
    {
        if ( ( sources == null ) || ( sources.size() == 0) )
            return " ";
        else {
            String clause = " AND sourceid IN ( "; boolean initial=true;
            for ( OrganisationUnit source: sources )
            {
                if (initial)
                {
                    initial = false;
                    clause = clause + " " + source.getId();
                }
                else
                {
                    clause = clause + ", " + source.getId();
                }

            }
            return clause +" ) ";
        }
    }

    private static String getPeriodsClause( Collection<Period> periods )
    {
        if ( ( periods == null ) || ( periods.size() == 0) )
            return " ";
        else {
            String clause = " AND periodid IN ( "; boolean initial=true;
            for ( Period p: periods )
            {
                Integer periodId = p.getId();
                if (periodId == null)
                {
                }
                else if (initial)
                {
                    initial = false;
                    clause = clause + " " + p.getId();
                }
                else
                {
                    clause = clause + ", " + p.getId();
                }

            }
            return clause +" ) ";
        }
    }

    public Set<DeflatedValidationResult> runValidationQuery( String sql )
    {

        SqlRowSet cursor = jdbcTemplate.queryForRowSet( sql );
        Set<DeflatedValidationResult> results = new HashSet<>();

        while ( cursor.next() )
        {
            DeflatedValidationResult vr = new DeflatedValidationResult(
                cursor.getInt( 1 ),
                cursor.getInt( 2 ),
                cursor.getInt( 3 ),
                cursor.getInt( 4 ),
                cursor.getDouble( 4 ),
                cursor.getDouble( 5 ) );
            results.add( vr );
        }

        return results;
    }

}
