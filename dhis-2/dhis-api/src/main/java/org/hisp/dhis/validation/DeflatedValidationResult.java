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

/**
 * The purpose of this class is to avoid the overhead of creating objects
 * for associated objects, in order to reduce heap space usage during export.
 * 
 * @author Lars Helge Overland
 * @version $Id$
 */
public class DeflatedValidationResult
{
    private int validationRuleId;
    
    private int periodId;
    
    private int sourceId;

    private int attributeOptionComboId;

    private Double leftSideValue;

    private Double rightSideValue;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public DeflatedValidationResult()
    {   
    }
    
    public DeflatedValidationResult ( Integer ruleId, Integer periodId, Integer sourceId, Integer attributeOptionComboId, Double leftSideValue, Double rightSideValue )
    {
        this.validationRuleId = ruleId;
        this.periodId = periodId;
        this.sourceId = sourceId;
        this.attributeOptionComboId = attributeOptionComboId;
        this.leftSideValue = leftSideValue;
        this.rightSideValue = rightSideValue;
    }

    // -------------------------------------------------------------------------
    // Getters and setters
    // -------------------------------------------------------------------------

    public int getValidationRuleId()
    {
        return validationRuleId;
    }

    public int getPeriodId()
    {
        return periodId;
    }

    public int getSourceId()
    {
        return sourceId;
    }

    public int getAttributeOptionComboId()
    {
        return attributeOptionComboId;
    }

    public Double getLeftSideValue()
    {
        return leftSideValue;
    }

    public Double getRightSideValue()
    {
        return rightSideValue;
    }


    // -------------------------------------------------------------------------
    // hashCode and equals
    // -------------------------------------------------------------------------

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        
        result = prime * result + validationRuleId;
        result = prime * result + periodId;
        result = prime * result + sourceId;
        result = prime * result + attributeOptionComboId;
        
        return result;
    }

    @Override
    public boolean equals( Object object )
    {
        if ( this == object )
        {
            return true;
        }
        
        if ( object == null )
        {
            return false;
        }
        
        if ( getClass() != object.getClass() )
        {
            return false;
        }
        
        final DeflatedValidationResult other = (DeflatedValidationResult) object;
        
        return validationRuleId == other.validationRuleId && 
	    periodId == other.periodId &&
            sourceId == other.sourceId && 
	    attributeOptionComboId == other.attributeOptionComboId &&
	    leftSideValue == other.leftSideValue &&
	    rightSideValue == other.rightSideValue;
    }
}
