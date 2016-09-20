package org.hisp.dhis.validation;

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

import org.hisp.dhis.common.SetMap;
import org.hisp.dhis.dataelement.DataElement;
import org.hisp.dhis.dataelement.DataElementCategoryService;
import org.hisp.dhis.datavalue.DataValueStore;
import org.hisp.dhis.datavalue.hibernate.HibernateDataValueStore;
import org.hisp.dhis.expression.ExpressionService;
import org.hisp.dhis.organisationunit.OrganisationUnit;
import org.hisp.dhis.organisationunit.OrganisationUnitService;
import org.hisp.dhis.period.Period;
import org.hisp.dhis.period.PeriodService;
import org.hisp.dhis.system.util.SystemUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.support.rowset.SqlRowSet;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.hisp.dhis.system.util.MathUtils.roundSignificant;

/**
 * Evaluates validation rules.
 * 
 * @author Jim Grace
 */
@Transactional
public class Validator
{

    @Autowired
    private static ExpressionService expressionService;

    public void setExpressionService( ExpressionService expressionService ) { this.expressionService = expressionService; }

    @Autowired
    private static PeriodService periodService;

    public void setPeriodService( PeriodService periodService )
    {
        this.periodService = periodService;
    }

    @Autowired
    private static OrganisationUnitService organisationUnitService;

    public void setOrganisationUnitService( OrganisationUnitService organisationUnitService )
    {
        this.organisationUnitService = organisationUnitService;
    }

    @Autowired
    private static DataValueStore dataValueStore;

    public void setDataValueStore( DataValueStore dataValueStore )
    {
        this.dataValueStore = dataValueStore;
    }

    @Autowired
    private static ValidationRuleService validationRuleService;

    public void setValidationRuleService( ValidationRuleService validationRuleService )
    {
        this.validationRuleService = validationRuleService;
    }

    /**
     * Evaluates validation rules for a collection of organisation units. This
     * method breaks the job down by organisation unit. It assigns the
     * evaluation for each organisation unit to a task that can be evaluated
     * independently in a multi-threaded environment.
     * 
     * @return a collection of any validations that were found
     */
    public static Collection<ValidationResult> validate( ValidationRunContext context, 
        ApplicationContext applicationContext )
    {
        DataElementCategoryService categoryService = (DataElementCategoryService) 
            applicationContext.getBean( DataElementCategoryService.class );
        Collection<PeriodTypeExtended> periodTypes = context.getPeriodTypeExtendedMap().values();
        Collection<OrganisationUnitExtended> sources = context.getSourceXs();
        Set<ValidationRule> applicableRules = new HashSet<ValidationRule>();
        Set<ValidationRule> simpleRules = new HashSet<ValidationRule>();
        Set<ValidationRule> rulesRun = context.getRulesRun();
        Map<OrganisationUnitExtended,Set<ValidationRule>> ruleMap =
                new Hashtable<OrganisationUnitExtended,Set<ValidationRule>>();
        SetMap<ValidationRule,Period> periodMap = new SetMap<ValidationRule,Period>();
        SetMap<ValidationRule,OrganisationUnit> sourceMap =
                new SetMap<ValidationRule,OrganisationUnit>();
        for ( OrganisationUnitExtended source: sources ) {
            Set<ValidationRule> sourceRules = new HashSet<ValidationRule>();
            for ( PeriodTypeExtended periodType: periodTypes )
            {
                Collection<DataElement> sourceDataElements =
                        periodType.getSourceDataElements().get( source.getSource() );
                Collection<ValidationRule> rules=
                        getRulesBySourceAndPeriodType( source, periodType, sourceDataElements);
                sourceRules.addAll( rules );
                applicableRules.addAll( rules );
                for ( ValidationRule rule: rules )
                {
                    periodMap.putValues( rule, periodType.getPeriods() );
                    sourceMap.putValue( rule, source.getSource() );
                }
            }
            ruleMap.put( source, sourceRules );
        }
        expressionService.explodeValidationRuleExpressions( applicableRules );

        if ( dataValueStore instanceof HibernateDataValueStore)
        {
            for ( ValidationRule vrule : applicableRules )
            {
                if ( vrule.getRuleType() == RuleType.SURVEILLANCE )
                {
                    continue;
                }
                else if ( (vrule.getLeftSide().getExpression().matches( ExpressionService.OPERAND_EXPRESSION )) &&
                        (vrule.getRightSide().getExpression().matches( ExpressionService.OPERAND_EXPRESSION )) )
                {
                    simpleRules.add( vrule );
                }
                else
                {
                    continue;
                }
            }
        }

        if ( dataValueStore instanceof HibernateDataValueStore )
        {
            HibernateDataValueStore hs = (HibernateDataValueStore) dataValueStore;
            Collection<ValidationResult> vresults = context.getValidationResults();
            for ( ValidationRule rule: simpleRules )
            {
                String sql = validationRuleService.getSQL( rule, periodMap.get( rule ), sourceMap.get( rule ) );
                if ( sql == null )
                {
                    continue;
                }
                // System.out.println("sql="+sql);
                SqlRowSet results = hs.rawQuery( sql );
                simpleRules.add( rule );
                while ( results.next() )
                {
                    ValidationResult vr = new ValidationResult ( periodService.getPeriod( results.getInt( 1 ) ),
                            organisationUnitService.getOrganisationUnit( results.getInt( 2 )),
                            categoryService.getDataElementCategoryOptionCombo( results.getInt( 3 ) ),
                            rule, roundSignificant( results.getDouble( 4 ) ), roundSignificant ( results.getDouble( 5 ) ));
                    System.out.println("Violation "+rule.getUid()+" at "+
                            organisationUnitService.getOrganisationUnit( results.getInt( 2 ) ).getName() +
                            " during " + periodService.getPeriod( results.getInt( 1 ) ).getIsoDate() +
                            " ( " + rule.getLeftSide().getExpression()+" "+
                            rule.getOperator().getMathematicalOperator() + " " +
                            rule.getRightSide().getExpression()+" )");
                    vresults.add( vr );

                }
            }
        }

        int threadPoolSize = getThreadPoolSize( context );
        ExecutorService executor = Executors.newFixedThreadPool( threadPoolSize );

        for ( OrganisationUnitExtended sourceX : context.getSourceXs() )
        {
            if ( sourceX.getToBeValidated() )
            {
                ValidationTask task = (ValidationTask) applicationContext.getBean( DataValidationTask.NAME );
                task.init( sourceX, context );
                
                executor.execute( task );
            }
        }

        executor.shutdown();

        try
        {
            executor.awaitTermination( 6, TimeUnit.HOURS );
        }
        catch ( InterruptedException e )
        {
            executor.shutdownNow();
        }

        reloadAttributeOptionCombos( context.getValidationResults(), categoryService );

        return context.getValidationResults();
    }

    /**
     * Determines how many threads we should use for testing validation rules.
     * 
     * @param context validation run context
     * @return number of threads we should use for testing validation rules
     */
    private static int getThreadPoolSize( ValidationRunContext context )
    {
        int threadPoolSize = SystemUtils.getCpuCores();

        if ( threadPoolSize > 2 )
        {
            threadPoolSize--;
        }

        if ( threadPoolSize > context.getCountOfSourcesToValidate() )
        {
            threadPoolSize = context.getCountOfSourcesToValidate();
        }

        return threadPoolSize;
    }

    /**
     * Gets the rules that should be evaluated for a given organisation unit and
     * period type.
     *
     * @param sourceX            the organisation unit extended information
     * @param periodTypeX        the period type extended information
     * @param sourceDataElements all data elements collected for this
     *                           organisation unit
     * @return set of rules for this org unit and period type
     */
    private static Set<ValidationRule> getRulesBySourceAndPeriodType(
            OrganisationUnitExtended sourceX, PeriodTypeExtended periodTypeX,
            Collection<DataElement> sourceDataElements )
    {
        Set<ValidationRule> periodTypeRules = new HashSet<>();

        for ( ValidationRule rule : periodTypeX.getRules() )
        {
            if ( rule.getRuleType() == RuleType.VALIDATION )
            {
                // For validation-type rules, include only rules where the
                // organisation collects all the data elements in the rule.
                // But if this is some funny kind of rule with no elements
                // (like for testing), include it also.
                Collection<DataElement> elements = rule.getCurrentDataElements();

                if ( elements == null || elements.size() == 0 || sourceDataElements.containsAll( elements ) )
                {
                    periodTypeRules.add( rule );
                }
            }
            else
            {
                // For surveillance-type rules, include only rules for this
                // organisation's unit level.
                // The organisation may not be configured for the data elements
                // because they could be aggregated from a lower level.
                if ( rule.getOrganisationUnitLevel() == sourceX.getLevel() )
                {
                    periodTypeRules.add( rule );
                }
            }
        }

        return periodTypeRules;
    }

    /**
     * Reload attribute category option combos into this Hibernate context.
     *
     * @param results
     * @param dataElementCategoryService
     */
    private static void reloadAttributeOptionCombos( Collection<ValidationResult> results,
        DataElementCategoryService dataElementCategoryService )
    {
        for ( ValidationResult result : results )
        {
            result.setAttributeOptionCombo( dataElementCategoryService
                .getDataElementCategoryOptionCombo( result.getAttributeOptionCombo().getId() ) );
        }
    }
}
