/*
 * Copyright Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags and
 * the COPYRIGHT.txt file distributed with this work.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.teiid.translator.couchbase;

import static org.teiid.language.SQLConstants.Reserved.*;
import static org.teiid.language.SQLConstants.Tokens.*;
import static org.teiid.query.metadata.DDLConstants.ANNOTATION;
import static org.teiid.query.metadata.DDLConstants.NAMEINSOURCE;
import static org.teiid.query.metadata.DDLConstants.NOT_NULL;
import static org.teiid.query.metadata.DDLConstants.UUID;
import static org.teiid.language.SQLConstants.isReservedWord;
import static org.teiid.translator.couchbase.CouchbaseProperties.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.teiid.core.types.DataTypeManager;
import org.teiid.core.util.StringUtil;
import org.teiid.language.SQLConstants.Tokens;
import org.teiid.metadata.Column;
import org.teiid.metadata.Datatype;
import org.teiid.metadata.MetadataFactory;
import org.teiid.metadata.Table;
import org.teiid.metadata.BaseColumn.NullType;
import org.teiid.query.sql.symbol.Constant;
import org.teiid.query.sql.visitor.SQLStringVisitor;

public class DDLStringVisitor {
    
    private static final HashSet<String> LENGTH_DATATYPES = new HashSet<String>(
            Arrays.asList(DataTypeManager.DefaultDataTypes.STRING, DataTypeManager.DefaultDataTypes.BIG_INTEGER));
    private static final HashSet<String> PRECISION_DATATYPES = new HashSet<String>(
            Arrays.asList(DataTypeManager.DefaultDataTypes.BIG_DECIMAL));
    
    private final static Map<String, String> BUILTIN_PREFIXES = new HashMap<String, String>();
    static {
        for (Map.Entry<String, String> entry : MetadataFactory.BUILTIN_NAMESPACES.entrySet()) {
            BUILTIN_PREFIXES.put(entry.getValue(), entry.getKey());
        }
    }
    
    private StringBuilder buffer = new StringBuilder();
 
    public static String getDDLString(List<Table> tables) {
        
        DDLStringVisitor visitor = new DDLStringVisitor();
        visitor.visit(tables);
        return visitor.toString();
    }
    
    public String toString() {
        return buffer.toString();
    }

    private void visit(List<Table> tables) {
        
        boolean first = true; 
        for(Table table : tables) {
            
            if (first) {
                first = false;
            } else {
                append(NEWLINE);
                append(NEWLINE);
            }
            
            append(CREATE).append(SPACE).append(FOREIGN_TABLE).append(SPACE);
            visitTableBody(table);
            append(SEMICOLON);
        }
    }

    private void visitTableBody(Table table) {
        
        String name = table.getName();
        append(escapeSinglePart(name));
        
        if (table.getColumns() != null) {
            append(SPACE);
            append(LPAREN);
            boolean first = true; 
            for (Column c:table.getColumns()) {
                if (first) {
                    first = false;
                }
                else {
                    append(COMMA);
                }
                visitColumn(table, c);
            }
            visitContraints(table);
            append(NEWLINE);
            append(RPAREN);
        }
        
        String options = buildTableOptions(table);      
        if (!options.isEmpty()) {
            append(SPACE).append(OPTIONS).append(SPACE).append(LPAREN).append(options).append(RPAREN);
        }
    }

    private String buildTableOptions(Table table) {
        // TODO Auto-generated method stub
        return null;
    }

    private void visitColumn(Table table, Column column) {

        append(NEWLINE).append(TAB);
        append(escapeSinglePart(column.getName()));
        
        Datatype datatype = column.getDatatype();
        String runtimeTypeName = column.getRuntimeType();
        boolean domain = false;
        if (datatype != null) {
            runtimeTypeName = datatype.getRuntimeTypeName();
            domain = datatype.getType() == Datatype.Type.Domain;
        }
        
        append(SPACE);
        
        if (domain) {
            append(datatype.getName());
        } else {
            append(runtimeTypeName);
            if (LENGTH_DATATYPES.contains(runtimeTypeName)) {
                if (column.getLength() != 0 && (datatype == null || column.getLength() != datatype.getLength())) {
                    append(LPAREN).append(column.getLength()).append(RPAREN);
                }
            } else if (PRECISION_DATATYPES.contains(runtimeTypeName) 
                    && !column.isDefaultPrecisionScale()) {
                append(LPAREN).append(column.getPrecision());
                if (column.getScale() != 0) {
                    append(COMMA).append(column.getScale());
                }
                append(RPAREN);
            }
        }
        
        if (datatype != null) {
            for (int dims = column.getArrayDimensions(); dims > 0; dims--) {
                append(LSBRACE).append(RSBRACE);
            }
        }
        if (column.getNullType() == NullType.No_Nulls && (!domain || datatype.getNullType() != NullType.No_Nulls)) {
            append(SPACE).append(NOT_NULL);
        }
        
        appendColumnOptions(column);
    }

    private void appendColumnOptions(Column column) {

        StringBuilder options = new StringBuilder();
        addCommonOptions(options, column);

        buildColumnOptions(column, options);
        
        if (options.length() != 0) {
            append(SPACE).append(OPTIONS).append(SPACE).append(LPAREN).append(options).append(RPAREN);
        }
    }

    private void addCommonOptions(StringBuilder sb, Column column) {

        if (column.isUUIDSet() && column.getUUID() != null && !column.getUUID().startsWith("tid:")) { //$NON-NLS-1$
            addOption(sb, UUID, column.getUUID());
        }
        if (column.getAnnotation() != null) {
            addOption(sb, ANNOTATION, column.getAnnotation());
        }
        if (column.getNameInSource() != null) {
            addOption(sb, NAMEINSOURCE, column.getNameInSource());
        }
    }

    private void addOption(StringBuilder sb, String key, Object value) {
        if (sb.length() != 0) {
            sb.append(COMMA).append(SPACE);
        }
        if (value != null) {
            value = new Constant(value);
        } else {
            value = Constant.NULL_CONSTANT;
        }
        if (key != null && key.length() > 2 && key.charAt(0) == '{') { 
            String origKey = key;
            int index = key.indexOf('}');
            if (index > 1) {
                String uri = key.substring(1, index);
                key = key.substring(index + 1, key.length());
                String prefix = BUILTIN_PREFIXES.get(uri);
                if ((prefix == null && usePrefixes) || createNS) {
                    if (prefixMap == null) {
                        prefixMap = new LinkedHashMap<String, String>();
                    } else {
                        prefix = this.prefixMap.get(uri);
                    }
                    if (prefix == null) {
                        prefix = "n"+this.prefixMap.size(); //$NON-NLS-1$                       
                    }
                    this.prefixMap.put(uri, prefix);
                } 
                if (prefix != null) {
                    key = prefix + ":" + key; //$NON-NLS-1$
                } else {
                    key = origKey;
                }
            }
        }
        sb.append(SQLStringVisitor.escapeSinglePart(key)).append(SPACE).append(value);
    }

    private void buildColumnOptions(Column column, StringBuilder options) {
        // TODO Auto-generated method stub
        
    }

    private String escapeSinglePart(String part) {
        if (isReservedWord(part)) {
            return ID_ESCAPE_CHAR + part + ID_ESCAPE_CHAR;
        }
        boolean escape = true;
        char start = part.charAt(0);
        if (start == '#' || start == '@' || StringUtil.isLetter(start)) { //$NON-NLS-1$ //$NON-NLS-2$
            escape = false;
            for (int i = 1; !escape && i < part.length(); i++) {
                char c = part.charAt(i);
                escape = !StringUtil.isLetterOrDigit(c) && c != '_'; //$NON-NLS-1$
            }
        }
        if (escape) {
            return ID_ESCAPE_CHAR + escapeStringValue(part, "\"") + ID_ESCAPE_CHAR; //$NON-NLS-1$
        }
        return part;
    }

    private String escapeStringValue(String str, String tick) {
        return StringUtil.replaceAll(str, tick, tick + tick);
    }

    private void visitContraints(Table table) {
        // TODO Auto-generated method stub
        
    }

    private DDLStringVisitor append(Object obj) {
        buffer.append(obj);
        return this;
    }
}
