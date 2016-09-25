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
import org.hisp.dhis.commons.util.DebugUtils;
import org.hisp.dhis.dataelement.DataElementCategoryService;
import org.hisp.dhis.datavalue.DataValueStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

/**
 * Runs a validation task on a thread within a multi-threaded validation run.
 * <p>
 * Each task looks for validation results in a different organisation unit.
 *
 * @author Ken Haase
 */
@Transactional
public class FastTrackValidationTask
    implements FastValidationTask
{
    public static final String NAME = "fastValidationTask";

    private static final Log log = LogFactory.getLog( FastTrackValidationTask.class );

    @Autowired
    private DataValueStore dataValueStore;

    public void setDataValueStore( DataValueStore dataValueStore )
    {
        this.dataValueStore = dataValueStore;
    }

    @Autowired
    private DataElementCategoryService categoryService;

    public void setCategoryService( DataElementCategoryService categoryService )
    {
        this.categoryService = categoryService;
    }

    private String validationQuery;
    private ValidationRule validationRule;
    private ValidationRunContext context;
    private ApplicationContext appContext;

    public FastTrackValidationTask()
    {
    }

    public FastTrackValidationTask( ValidationRule rule, String query, ValidationRunContext context,
        ApplicationContext appContext )
    {
        this.validationRule = rule;
        this.validationQuery = query;
        this.context = context;
        this.appContext = appContext;
    }

    public void init( ValidationRule rule, String query, ValidationRunContext context,
        ApplicationContext appContext )
    {
        this.validationRule = rule;
        this.validationQuery = query;
        this.context = context;
        this.appContext = appContext;
    }

    public String getQuery()
    {
        return validationQuery;
    }

    // Logging

    private void dolog( double start, String msg )
    {
        System.out.println( System.out.printf( "%.6f", ((System.nanoTime()-start)/1000000000) ) + msg );
    }

    /**
     * Evaluates validation rules for a single organisation unit. This is the
     * central method in validation rule evaluation.
     */
    @Transactional
    public void run()
    {
        try
        {
            runInternal();
        }
        catch ( RuntimeException ex )
        {
            log.error( DebugUtils.getStackTrace( ex ) );

            throw ex;
        }
    }

    private void runInternal()
    {
        Set<DeflatedValidationResult> rawResults = dataValueStore.runValidationQuery( this.validationQuery );
        Set<ValidationResult> violations = new HashSet<>();
        double startTime = System.nanoTime();

        for ( DeflatedValidationResult r : rawResults )
        {
            ValidationResult vr = new ValidationResult( context.getPeriod( r.getPeriodId() ),
                context.getSource( r.getSourceId() ),
                categoryService.getDataElementCategoryOptionCombo( r.getAttributeOptionComboId() ),
                context.getValidationRule( r.getValidationRuleId() ),
                r.getLeftSideValue(),
                r.getRightSideValue() );

            violations.add( vr );
        }

        dolog( startTime,
            violations.size() + " violations from fast track for " +
                validationRule.getUid() + " (" + validationRule.getDescription() + ") " +
                " from " + rawResults.size() + " raw results.");

        context.getValidationResults().addAll( violations );

    }


}
