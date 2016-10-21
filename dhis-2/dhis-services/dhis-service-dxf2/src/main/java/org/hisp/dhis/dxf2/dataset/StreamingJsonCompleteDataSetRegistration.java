package org.hisp.dhis.dxf2.dataset;

/*
 * Copyright (c) 2004-2015, University of Oslo
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

import com.fasterxml.jackson.core.JsonGenerator;

import java.io.IOException;

/**
 * @author Halvdan Hoem Grelland
 */
public class StreamingJsonCompleteDataSetRegistration
    extends CompleteDataSetRegistration
{
    private JsonGenerator generator;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public StreamingJsonCompleteDataSetRegistration( JsonGenerator generator )
    {
        this.generator = generator;

        try
        {
            generator.writeStartObject();
        }
        catch ( IOException e )
        {
            // Intentionally ignored
        }
    }

    // -------------------------------------------------------------------------
    // Logic
    // -------------------------------------------------------------------------

    @Override
    public void close()
    {
        if ( generator == null )
        {
            return;
        }

        try
        {
            generator.writeEndObject();
        }
        catch ( IOException e )
        {
            // Intentionally ignored
        }
    }

    // -------------------------------------------------------------------------
    // Setter overrides
    // -------------------------------------------------------------------------

    @Override
    public void setDataSet( String dataSet )
    {
        writeObjectField( "dataSet", dataSet );
    }

    @Override
    public void setPeriod( String period )
    {
        writeObjectField( "period", period );
    }

    @Override
    public void setOrganisationUnit( String organisationUnit )
    {
        writeObjectField( "organisationUnit", organisationUnit );
    }

    @Override
    public void setAttributeOptionCombo( String attributeOptionCombo )
    {
        writeObjectField( "attributeOptionCombo", attributeOptionCombo );
    }

    @Override
    public void setDate( String date )
    {
        writeObjectField( "date", date );
    }

    @Override
    public void setStoredBy( String storedBy )
    {
        writeObjectField( "storedBy", storedBy );
    }

    // -------------------------------------------------------------------------
    // Supportive methods
    // -------------------------------------------------------------------------

    private void writeObjectField( String fieldName, String value )
    {
        if ( value == null )
        {
            return;
        }

        try
        {
            generator.writeObjectField( fieldName, value );
        }
        catch ( IOException e )
        {
            // Intentionally ignored
        }
    }
}