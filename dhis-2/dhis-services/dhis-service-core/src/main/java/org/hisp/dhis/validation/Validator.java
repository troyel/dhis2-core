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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hisp.dhis.common.GenericIdentifiableObjectStore;
import org.hisp.dhis.common.SetMap;
import org.hisp.dhis.constant.ConstantService;
import org.hisp.dhis.dataelement.DataElement;
import org.hisp.dhis.dataelement.DataElementCategoryService;
import org.hisp.dhis.datavalue.DataValueService;
import org.hisp.dhis.datavalue.DataValueStore;
import org.hisp.dhis.expression.ExpressionService;
import org.hisp.dhis.message.MessageService;
import org.hisp.dhis.organisationunit.OrganisationUnit;
import org.hisp.dhis.organisationunit.OrganisationUnitService;
import org.hisp.dhis.period.Period;
import org.hisp.dhis.period.PeriodService;
import org.hisp.dhis.setting.SystemSettingManager;
import org.hisp.dhis.system.util.SystemUtils;
import org.hisp.dhis.user.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Evaluates validation rules.
 * 
 * @author Jim Grace
 */
@Transactional
public class Validator
{
    private static final Log log = LogFactory.getLog( Validator.class );

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private ValidationRuleStore validationRuleStore;

    public void setValidationRuleStore( ValidationRuleStore validationRuleStore )
    {
        this.validationRuleStore = validationRuleStore;
    }

    private ValidationRuleService validationRuleService;

    public void setValidationRuleService( ValidationRuleService validationRuleService )
    {
        this.validationRuleService = validationRuleService;
    }

    private GenericIdentifiableObjectStore<ValidationRuleGroup> validationRuleGroupStore;

    public void setValidationRuleGroupStore( GenericIdentifiableObjectStore<ValidationRuleGroup> validationRuleGroupStore )
    {
        this.validationRuleGroupStore = validationRuleGroupStore;
    }

    private PeriodService periodService;

    public void setPeriodService( PeriodService periodService )
    {
        this.periodService = periodService;
    }

    private DataValueService dataValueService;

    public void setDataValueService( DataValueService dataValueService )
    {
        this.dataValueService = dataValueService;
    }

    private ExpressionService expressionService;

    public void setExpressionService( ExpressionService expressionService ) { this.expressionService = expressionService; }

    private ConstantService constantService;

    public void setConstantService( ConstantService constantService )
    {
        this.constantService = constantService;
    }

    private DataElementCategoryService categoryService;

    public void setCategoryService( DataElementCategoryService categoryService )
    {
        this.categoryService = categoryService;
    }

    @Autowired
    private DataValueStore dataValueStore;

    public void setDataValueStore( DataValueStore dataValueStore )
    {
        this.dataValueStore = dataValueStore;
    }

    private MessageService messageService;

    public void setMessageService( MessageService messageService )
    {
        this.messageService = messageService;
    }

    private OrganisationUnitService organisationUnitService;

    public void setOrganisationUnitService( OrganisationUnitService organisationUnitService )
    {
        this.organisationUnitService = organisationUnitService;
    }

    private CurrentUserService currentUserService;

    public void setCurrentUserService( CurrentUserService currentUserService )
    {
        this.currentUserService = currentUserService;
    }

    private SystemSettingManager systemSettingManager;

    public void setSystemSettingManager( SystemSettingManager systemSettingManager )
    {
        this.systemSettingManager = systemSettingManager;
    }

    // Logging

    private void dolog( double start, String msg )
    {
        System.out.println( System.out.printf( "%.6f", ((System.nanoTime()-start)/1000000000) ) + msg );
    }

    @Autowired
    private ApplicationContext applicationContext;

    /* Actual Validation
         * Evaluates validation rules for a collection of organisation units. This
         * method breaks the job down by organisation unit. It assigns the
         * evaluation for each organisation unit to a task that can be evaluated
         * independently in a multi-threaded environment.
         *
         * @return a collection of any validations that were found
         */
    public Collection<ValidationResult> validate( ValidationRunContext context,
        ApplicationContext applicationContext )
    {
        long startTime = System.nanoTime();
        DataElementCategoryService categoryService = (DataElementCategoryService)
            applicationContext.getBean( DataElementCategoryService.class );
        Collection<PeriodTypeExtended> periodTypes = context.getPeriodTypeExtendedMap().values();
        Collection<OrganisationUnitExtended> sources = context.getSourceXs();
        Set<ValidationRule> applicableRules = new HashSet<ValidationRule>();
        Set<ValidationRule> skipRules = new HashSet<>();
        Map<OrganisationUnitExtended, Set<ValidationRule>> ruleMap =
            new Hashtable<OrganisationUnitExtended, Set<ValidationRule>>();
        SetMap<ValidationRule, Period> periodMap = new SetMap<ValidationRule, Period>();
        SetMap<ValidationRule, OrganisationUnit> sourceMap =
            new SetMap<ValidationRule, OrganisationUnit>();
        Map<ValidationRule, String> validationQueries = new Hashtable<>();

        for ( OrganisationUnitExtended source : sources )
        {
            Set<ValidationRule> sourceRules = new HashSet<ValidationRule>();
            for ( PeriodTypeExtended periodType : periodTypes )
            {
                Collection<DataElement> sourceDataElements =
                    periodType.getSourceDataElements().get( source.getSource() );
                Collection<ValidationRule> rules =
                    getRulesBySourceAndPeriodType( source, periodType, sourceDataElements, context );
                for ( ValidationRule rule : rules )
                {
                    if (rule.getRuleType() == RuleType.VALIDATION )
                    {
                        periodMap.putValues( rule, periodType.getPeriods() );
                        sourceMap.putValue( rule, source.getSource() );
                        applicableRules.add( rule );
                    }
                    else
                    {
                        skipRules.add( rule );
                    }
                }
                sourceRules.addAll( rules );
            }
            ruleMap.put( source, sourceRules );
        }
        expressionService.explodeValidationRuleExpressions( applicableRules );

        Set<ValidationResult> violations = new HashSet<>();
        for ( ValidationRule rule : applicableRules )
        {
            dolog( startTime, "Processing rule " + rule.getUid() + ": " + rule.getDescription() );
            String leftExpression = rule.getLeftSide().getExpression();
            String rightExpression = rule.getRightSide().getExpression();
            String query = dataValueStore.getValidationQuery( rule, leftExpression, rightExpression,
                expressionService.getDataInputsInExpression( leftExpression ),
                expressionService.getDataInputsInExpression( rightExpression ),
                periodMap.get( rule ), sourceMap.get( rule ),
                context.getCogDimensionConstraints(),
                context.getCoDimensionConstraints(),
                categoryService.getDefaultDataElementCategoryCombo() );

            if ( query == null )
            {
                dolog( startTime, "No fast track for " + rule.getUid() + " (" + rule.getDescription() + ") " +
                    leftExpression +
                    " " + rule.getOperator().getMathematicalOperator() + " " +
                    rightExpression );
                continue;
            }
            else
            {
                dolog( startTime, "Fast track for " + rule.getUid() + " (" + rule.getDescription() + ") " +
                    leftExpression +
                    " " + rule.getOperator().getMathematicalOperator() + " " +
                    rightExpression );
                dolog( startTime, "Fast track for " + rule.getUid() + " (" + rule.getDescription() + ") : \n " + query );
                validationQueries.put( rule, query );
                skipRules.add( rule );
            }
        }

        dolog( startTime,
            "Analyzed " + applicableRules.size() + " validation rules, " +
            "yielding " + validationQueries.size() + " fast track rules and " +
            (applicableRules.size() - validationQueries.size() ) + " regular cases." );

        int threadPoolSize = getThreadPoolSize( context );
        ExecutorService executor = Executors.newFixedThreadPool( threadPoolSize );

        for ( Map.Entry<ValidationRule, String> ruleEntry : validationQueries.entrySet() )
        {
            FastValidationTask fastTask = (FastValidationTask) applicationContext.getBean( FastTrackValidationTask.NAME );
            fastTask.init( ruleEntry.getKey(), ruleEntry.getValue(), context, applicationContext );

            executor.execute( fastTask );
        }

        if ( applicableRules.size() > validationQueries.size() )
        {
            dolog( startTime, "Processing org units, skipping " + skipRules.size() + " rules");
            for ( OrganisationUnitExtended sourceX : context.getSourceXs() )
            {
                if ( sourceX.getToBeValidated() )
                {
                    ValidationTask task = (ValidationTask) applicationContext.getBean( DataValidationTask.NAME );
                    task.init( sourceX, context, skipRules );

                    executor.execute( task );
                }
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

        dolog( startTime,
            "Finished processing validation rules using " + threadPoolSize + " threads and identifying " +
            context.getValidationResults().size() + " violations");

        reloadAttributeOptionCombos( context.getValidationResults(), categoryService );

        return context.getValidationResults();
    }

    /**
     * Determines how many threads we should use for testing validation rules.
     *
     * @param context validation run context
     * @return number of threads we should use for testing validation rules
     */
    private int getThreadPoolSize( ValidationRunContext context )
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
     * Reload attribute category option combos into this Hibernate context.
     *
     * @param results
     * @param dataElementCategoryService
     */
    private void reloadAttributeOptionCombos( Collection<ValidationResult> results,
        DataElementCategoryService dataElementCategoryService )
    {
        for ( ValidationResult result : results )
        {
            result.setAttributeOptionCombo( dataElementCategoryService
                .getDataElementCategoryOptionCombo( result.getAttributeOptionCombo().getId() ) );
        }
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
    public Set<ValidationRule> getRulesBySourceAndPeriodType(
        OrganisationUnitExtended sourceX, PeriodTypeExtended periodTypeX,
        Collection<DataElement> sourceDataElements,
        ValidationRunContext context )
    {
        Set<ValidationRule> matchingRules = new HashSet<>();

        for ( ValidationRule rule : periodTypeX.getRules() )
        {
            Collection<DataElement> elements = rule.getCurrentDataElements();

                if ( elements == null || elements.size() == 0 || sourceDataElements.containsAll( elements ) )
                {
                    matchingRules.add( rule );
                }
        }

        return matchingRules;
    }

}
