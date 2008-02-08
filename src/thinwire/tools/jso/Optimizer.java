/*
                          ThinWire(R) JavaScript Optimizer
                        Copyright (C) 2003-2008 ThinWire LLC

  This library is free software; you can redistribute it and/or modify it under
  the terms of the GNU Lesser General Public License as published by the Free
  Software Foundation; either version 2.1 of the License, or (at your option) any
  later version.

  This library is distributed in the hope that it will be useful, but WITHOUT ANY
  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
  PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.

  You should have received a copy of the GNU Lesser General Public License along
  with this library; if not, write to the Free Software Foundation, Inc., 59
  Temple Place, Suite 330, Boston, MA 02111-1307 USA

  Users who would rather have a commercial license, warranty or support should
  contact the following company who supports the technology:
  
            ThinWire LLC, 5919 Greenville #335, Dallas, TX 75206-1906
   	            email: info@thinwire.com    ph: +1 (214) 295-4859
 	                        http://www.thinwire.com
*/
package thinwire.tools.jso;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Token;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.NativeFunction;

/**
 * Inspired by the org.mozilla.javascript.Decompliler class that is part of the Mozilla
 * Rhino distribution. However this class has been written from the ground up to do
 * something quite different. Running this tool over a JavaScript source file will
 * result in significant code size reduction. Multiple techniques are used to
 * achieve the reduction:
 * 
 * 1. All removable whitespace is stripped.
 * 2. All line-ending ';' are replaced with single '\n' characters. This accomplishes
 *    the same reduction as the opposite approach of using a ';' for all line breaks
 *    and striping all '\n' characters, except that it makes it easier to trouble
 *    shoot issues when they arise.
 * 3. All object references with text names that occur multiple times such as
 *    'elem.style.backgrondColor' are reduced to direct hash level access such as
 *    elem[A][B], and the individual parts like 'style' and 'backgroundColor' are
 *    placed into variables in the global scope. The most frequently occurring
 *    object properties are given the shortest names.  
 */
public final class Optimizer {        
    private static final int FUNCTION_END = Token.LAST_TOKEN + 1;
    private static final char[] VALID_NAME_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_$0123456789".toCharArray();
    private static final Pattern REGEX_VALID_NAME = Pattern.compile("[a-zA-Z_$][a-zA-Z0-9_$]*");
    private static final Pattern REGEX_DOUBLE_SLASH = Pattern.compile("\\\\");
    private static final Pattern REGEX_DOUBLE_QUOTE = Pattern.compile("\"");    
    private static final Pattern REGEX_CRLF = Pattern.compile("\\r?\\n");
    private static final Pattern REGEX_UNICODE = Pattern.compile("([\u0000-\u001f]|[\u007f-\uffff])");
    private static final String[] RESERVED_NAMES = {
        "this","function","new","delete","if",
        "else","for","in","with","while","do","try","catch","finally","throw","switch",
        "goto","break","continue","case","default","return","var","instanceof","typeof",
        "void","int","byte","short","long","char","boolean" 
    };
    
    static {
        Arrays.sort(RESERVED_NAMES);
    }
    
    private enum ScopeState {
        NONE, START_FUNCTION, START_ARGS, START_FOR,          
    }
    
    private static class BlockInfo {
        int token;
        int startIndex = -1;        
        int stmtCnt;
    }
    
    private int nameNum;
    private List<BlockInfo> blockStack;
    private Set<String> usedNameMap;
    private Map<String, String> varMap;
    private Set<String> constSet;
    private ScopeState scopeState;
    private int scopeParen;
    private String scopeQualifier;    
    private boolean analyzeNames;
    private int priorToken;
    private int currentToken;
    
    public Optimizer() {
        blockStack = new ArrayList<BlockInfo>();
        usedNameMap = new HashSet<String>();
        varMap = new HashMap<String, String>();
        constSet = new HashSet<String>();        
        constSet.add("true");
        constSet.add("false");
        constSet.add("null");
        constSet.add("undefined");
        constSet.add("NaN");
        constSet.add("Infinity");
        constSet.add("Array");
        constSet.add("Boolean");
        constSet.add("Date");
        constSet.add("Function");
        constSet.add("Math");
        constSet.add("Number");
        constSet.add("Object");
        constSet.add("RegExp");
        constSet.add("String");
        constSet.add("parseFloat");
        constSet.add("parseInt");
        constSet.add("isFinite");
        constSet.add("isNaN");
        reset();
    }
    
    public void reset() {
        nameNum = 0;
        blockStack.clear();
        newBlock(-1);
        usedNameMap.clear();
        varMap.clear();
        scopeState = ScopeState.NONE;
        scopeParen = 0;
        scopeQualifier = "";
        priorToken = 0;
    }
    
    //First pass builds nameMap;
    public void analyzeNames(Script script) {
        analyzeNames = true;
        decompile(script);        
    }
    
    //Second pass constructs name map and returns optimized code
    public String generate(Script script) {
        if (analyzeNames) {
            analyzeNames = false;
            assignNames(varMap);
        }
        
        return decompile(script);
    }
    
    public String getNameMapScript() {
        if (analyzeNames) {
            analyzeNames = false;
            assignNames(varMap);
        }

        StringBuffer sb = new StringBuffer();
        
        for (Map.Entry<String, String> e : varMap.entrySet()) {
            String name = e.getValue();
            String value = e.getKey();
            boolean asQuotedString = !constSet.contains(value);
            sb.append(name).append('=');
            if (asQuotedString) sb.append('"');
            sb.append(ScriptRuntime.escapeString(value));
            if (asQuotedString) sb.append('"');
            sb.append('\n');
        }
        
        return sb.toString();
    }
    
    private void assignNames(Map<String, String> nameMap) {
        List<Map.Entry<String, String>> memberCount = new ArrayList<Map.Entry<String, String>>(nameMap.entrySet());
        Collections.sort(memberCount, new Comparator<Map.Entry<String, String>>() {
            public int compare(Entry<String, String> o1, Entry<String, String> o2) {
                int i1 = Integer.parseInt(o1.getValue());
                int i2 = Integer.parseInt(o2.getValue());
                if (i1 > i2) return -1;
                else if (i1 < i2) return 1;
                else return 0;
            }
        });
        
        for (Map.Entry<String, String> e : memberCount) {
            int count = Integer.parseInt(e.getValue());

            if (count == 1) {
                nameMap.remove(e.getKey());
            } else {
                nameMap.put(e.getKey(), getNextName());
            }
        }
    }
    
    String getChangedName(String name) {
        return name;
    }
    
    private void newBlock(int token) {
        addBlockStatement();
        BlockInfo bi = new BlockInfo();
        bi.token = token;
        blockStack.add(bi);
    }
    
    private void addBlockStatement() {
        if (blockStack.size() - 1 >= 0) blockStack.get(blockStack.size() - 1).stmtCnt++;
    }
       
    private String decompile(Script script) {                
        String source = ((NativeFunction)script).getEncodedSource();
        int length = source.length();
        if (length == 0) { return ""; }
        int i = source.charAt(0) == Token.SCRIPT ? 1 : 0;
        
        StringBuffer result = new StringBuffer();

        synchronized (result) {
            while (i < length) {
                priorToken = currentToken;
                currentToken = source.charAt(i);
                switch(currentToken) {
                case Token.NAME:
                case Token.REGEXP:  // re-wrapped in '/'s in parser...
                    if (priorToken == Token.FUNCTION) result.append(' ');
                    i = printSourceString(source, i + 1, false, result);
                    continue;
    
                case Token.STRING:
                    i = printSourceString(source, i + 1, true, result);
                    continue;
    
                case Token.NUMBER:
                    i = printSourceNumber(source, i + 1, result);
                    continue;
    
                case Token.TRUE:
                    processName(result, "true", false);
                    break;
    
                case Token.FALSE:
                    processName(result, "false", false);
                    break;
    
                case Token.NULL:
                    processName(result, "null", false);
                    break;
    
                case Token.THIS:
                    result.append("this");
                    break;
    
                case Token.FUNCTION:
                    result.append("function");                        
                    scopeState = ScopeState.START_FUNCTION;
                    scopeQualifier += ".anon_" + i;
                    ++i; // skip function type
                    break;
    
                case FUNCTION_END:
                    scopeQualifier = scopeQualifier.substring(0, scopeQualifier.lastIndexOf('.'));
                    break;
    
                case Token.COMMA:
                    result.append(',');
                    break;
    
                case Token.LC:
                    result.append('{');
                    
                    if (scopeState == ScopeState.START_ARGS) {
                        scopeState = ScopeState.NONE;
                    } else {
                        if (blockStack.size() - 1 >= 0) {
                            BlockInfo bi = blockStack.get(blockStack.size() - 1);
                            
                            if (bi.startIndex == -1) {
                                bi.startIndex = result.length() - 1;
                            } else {
                                newBlock(Token.OBJLIT);
                            }
                            
                        }
                    }
                    
                    break;
    
                case Token.RC:
                    if (FUNCTION_END != getNext(source, length, i)) {
                        if (blockStack.size() - 1 >= 0) {
                            BlockInfo bi = blockStack.remove(blockStack.size() - 1);
                            
                            if (bi.stmtCnt == 1 && bi.token != Token.OBJLIT && bi.token != Token.TRY && bi.token != Token.CATCH && bi.token != Token.FINALLY) {
                                if (bi.startIndex != -1 && bi.token == Token.IF) {
                                    //System.out.println(bi.token);
                                    //result.deleteCharAt(bi.startIndex);
                                    result.append('}');
                                } else {
                                    result.append('}');
                                    //System.out.println(bi.token);
                                }
                            } else {
                                result.append('}');
                            }
                        } else {
                            result.append('}');
                        }
                    } else {                    
                        result.append('}');
                    }
                    
                    break;
                    
                case Token.LP:
                    result.append('(');
                    
                    if (scopeState == ScopeState.START_FUNCTION) {
                        scopeState = ScopeState.START_ARGS;
                    } else if (scopeState == ScopeState.START_FOR) {
                        scopeParen++;
                    }
                    
                    break;
    
                case Token.RP:                    
                    result.append(')');
                    
                    if (scopeState == ScopeState.START_FOR) {
                        scopeParen--;
                        if (scopeParen == 0) scopeState = ScopeState.NONE;
                    }
                    
                    break;
    
                case Token.LB:
                    result.append('[');
                    break;
    
                case Token.RB:
                    result.append(']');
                    break;
    
                case Token.EOL:
                    //Do nothing
                    break;
                
                case Token.DOT:
                    result.append('.');
                    break;
    
                case Token.NEW:
                    result.append("new ");
                    break;
    
                case Token.DELPROP:
                    result.append("delete ");
                    break;
    
                case Token.IF:
                    result.append("if");
                    newBlock(currentToken);
                    break;
    
                case Token.ELSE:
                    result.append("else");
                    newBlock(currentToken);
                    break;
    
                case Token.FOR:
                    result.append("for");
                    scopeState = ScopeState.START_FOR;
                    newBlock(currentToken);
                    break;
    
                case Token.IN:
                    result.append(" in ");
                    break;
    
                case Token.WITH:
                    result.append("with");
                    newBlock(currentToken);
                    break;
    
                case Token.WHILE:
                    result.append("while");
                    newBlock(currentToken);
                    break;
    
                case Token.DO:
                    result.append("do ");
                    newBlock(currentToken);
                    break;
    
                case Token.TRY:
                    result.append("try");
                    newBlock(currentToken);
                    break;
    
                case Token.CATCH:
                    result.append("catch");
                    newBlock(currentToken);
                    break;
    
                case Token.FINALLY:
                    result.append("finally");
                    newBlock(currentToken);
                    break;
    
                case Token.THROW:
                    result.append("throw ");
                    break;
    
                case Token.SWITCH:
                    result.append("switch");
                    newBlock(currentToken);
                    break;
    
                case Token.GOTO:                
                    result.append("goto");
                    if (Token.NAME == getNext(source, length, i)) result.append(' ');
                    break;
                
                case Token.BREAK:
                    result.append("break");
                    if (Token.NAME == getNext(source, length, i)) result.append(' ');
                    break;
    
                case Token.CONTINUE:
                    result.append("continue");
                    if (Token.NAME == getNext(source, length, i)) result.append(' ');
                    break;
    
                case Token.CASE:
                    result.append("case ");
                    break;
    
                case Token.DEFAULT:
                    result.append("default");
                    break;
    
                case Token.RETURN:
                    result.append("return");
                    if (Token.SEMI != getNext(source, length, i)) result.append(' ');
                    break;
    
                case Token.VAR:
                    if (scopeQualifier.length() > 0) result.append("var ");
                    break;
    
                case Token.SEMI:
                    if (scopeState == ScopeState.START_FOR) {
                        result.append(';');
                    } else {
                        result.append('\n');
                        addBlockStatement();
                    }
                    
                    break;
    
                case Token.ASSIGN:
                    result.append("=");
                    break;
    
                case Token.ASSIGNOP:
                    ++i;
                    switch (source.charAt(i)) {
                    case Token.ADD:
                        result.append("+=");
                        break;
    
                    case Token.SUB:
                        result.append("-=");
                        break;
    
                    case Token.MUL:
                        result.append("*=");
                        break;
    
                    case Token.DIV:
                        result.append("/=");
                        break;
    
                    case Token.MOD:
                        result.append("%=");
                        break;
    
                    case Token.BITOR:
                        result.append("|=");
                        break;
    
                    case Token.BITXOR:
                        result.append("^=");
                        break;
    
                    case Token.BITAND:
                        result.append("&=");
                        break;
    
                    case Token.LSH:
                        result.append("<<=");
                        break;
    
                    case Token.RSH:
                        result.append(">>=");
                        break;
    
                    case Token.URSH:
                        result.append(">>>=");
                        break;
                    }
                    break;
    
                case Token.HOOK:
                    result.append("?");
                    break;
    
                case Token.OBJLIT:
                case Token.COLON:
                    result.append(':');
                    break;
    
                case Token.OR:
                    result.append("||");
                    break;
    
                case Token.AND:
                    result.append("&&");
                    break;
    
                case Token.BITOR:
                    result.append("|");
                    break;
    
                case Token.BITXOR:
                    result.append("^");
                    break;
    
                case Token.BITAND:
                    result.append("&");
                    break;
    
                case Token.SHEQ:
                    result.append("===");
                    break;
    
                case Token.SHNE:
                    result.append("!==");
                    break;
    
                case Token.EQ:
                    result.append("==");
                    break;
    
                case Token.NE:
                    result.append("!=");
                    break;
    
                case Token.LE:
                    result.append("<=");
                    break;
    
                case Token.LT:
                    result.append("<");
                    break;
    
                case Token.GE:
                    result.append(">=");
                    break;
    
                case Token.GT:
                    result.append(">");
                    break;
    
                case Token.INSTANCEOF:
                    result.append(" instanceof ");
                    break;
    
                case Token.LSH:
                    result.append("<<");
                    break;
    
                case Token.RSH:
                    result.append(">>");
                    break;
    
                case Token.URSH:
                    result.append(">>>");
                    break;
    
                case Token.TYPEOF:
                    result.append("typeof ");
                    break;
    
                case Token.VOID:
                    result.append("void ");
                    break;
    
                case Token.NOT:
                    result.append('!');
                    break;
    
                case Token.BITNOT:
                    result.append('~');
                    break;
    
                case Token.POS:
                    result.append(" +");
                    break;
    
                case Token.NEG:
                    result.append(" -");
                    break;
    
                case Token.INC:
                    result.append("++");
                    break;
    
                case Token.DEC:
                    result.append("--");
                    break;
    
                case Token.ADD:
                    result.append('+');
                    break;
    
                case Token.SUB:
                    result.append('-');
                    break;
    
                case Token.MUL:
                    result.append('*');
                    break;
    
                case Token.DIV:
                    result.append('/');
                    break;
    
                case Token.MOD:
                    result.append('%');
                    break;
    
                default:
                    // If we don't know how to decompile it, raise an exception.
                    throw new RuntimeException();
                }
                
                ++i;
            }
        }
        
        return result.toString();
    }
    
    private int getNext(String source, int length, int i) {
        return (i + 1 < length) ? source.charAt(i + 1) : Token.EOF;
    }

    private boolean processName(StringBuffer sb, String str, boolean asQuotedString) {
        boolean replaced = false;
        
        if (analyzeNames) {
            String sCount = varMap.get(str);
            varMap.put(str, String.valueOf(sCount == null ? 1 : Integer.parseInt(sCount) + 1));
            if (asQuotedString) str = "\"" + str + "\"";
        } else {
            String name = varMap.get(str);
            
            if (name == null) {
                if (asQuotedString) str = "\"" + str + "\"";
            } else {
                replaced = true;
                str = name;
            }
        }
        
        sb.append(str);
        return replaced;
    }
    
    private int printSourceString(String source, int offset, boolean asQuotedString, StringBuffer sb) {
        int length = source.charAt(offset);
        ++offset;
        
        if ((0x8000 & length) != 0) {
            length = ((0x7FFF & length) << 16) | source.charAt(offset);
            ++offset;
        }
        
        if (sb != null) {
            String str = source.substring(offset, offset + length);
            
            if (asQuotedString) {
                str = REGEX_DOUBLE_SLASH.matcher(str).replaceAll("\\\\\\\\");
                str = REGEX_DOUBLE_QUOTE.matcher(str).replaceAll("\\\\\"");
                str = REGEX_CRLF.matcher(str).replaceAll("\\\\r\\\\n");
                
                Matcher matcher = REGEX_UNICODE.matcher(str);
                StringBuffer sbu = new StringBuffer();
                
                while (matcher.find()) {
                    String hexCode = Integer.toHexString(matcher.group(1).charAt(0));
                    matcher.appendReplacement(sbu, "\\\\u");
                    for (int num = 4 - hexCode.length(); --num >= 0;) sbu.append('0');
                    sbu.append(hexCode);
                }
                
                matcher.appendTail(sbu);
                str = sbu.toString();
                
                processName(sb, str, asQuotedString);
            } else if (priorToken == Token.DOT) {
                if (processName(sb, str, asQuotedString)) {
                    sb.setCharAt(sb.lastIndexOf("."), '[');
                    sb.append(']');
                }
            } else if (constSet.contains(str)) {
                processName(sb, str, asQuotedString);
            } else {
                //If the function was just declared and a name follows, it is a named function so switch out the anon name.
                //if (scopeState == ScopeState.START_FUNCTION) scopeStack.set(0, str);

                //Qualify all function arguments and variables with the fuction name.
                /*if (scopeState == ScopeState.START_ARGS || (priorToken == Token.VAR && scopeQualifier.length() > 0)) {
                    String qualName = scopeQualifier + "." + str;
                    
                    if (buildNameMap) {
                        if (isExcludedName(qualName)) {
                            sb.append(str);
                        } else {
                            String name = funcNameMap.get(qualName);
                            
                            if (name == null) {
                                String sNameNum = funcNameMap.get(scopeQualifier);
                                int nameNum = sNameNum == null ? 0 : Integer.parseInt(sNameNum);
                                
                                do {            
                                    name = toBase(nameNum++, VALID_NAME_CHARS);
                                } while (!isValidName(name));
                                                              
                                funcNameMap.put(scopeQualifier, String.valueOf(nameNum));
                                funcNameMap.put(qualName, name);
                            }
                            
                            //System.out.println("qualName=" + qualName + ",name=" + name);
                            sb.append(name);
                        }
                    } else {
                        String name = funcNameMap.get(qualName);
                        System.out.println("qualName=" + qualName + ",name=" + name);
                        if (name == null) name = str;
                        sb.append(name);
                        
                    }
                } else {*/
                    usedNameMap.add(str);
                    sb.append(str);
                //}
            }
        }
        
        return offset + length;
    }
    
    private String getNextName() {
        String name;
        
        do {            
            name = toBase(nameNum++, VALID_NAME_CHARS);
        } while (!isValidName(name) || usedNameMap.contains(name));
        
        return name;
    }
    
    private static String toBase(int i, char[] digits) {
        int radix = digits.length;
        char buf[] = new char[33];
        boolean negative = i < 0;
        int charPos = 32;
        if (!negative) i = -i;

        while (i <= -radix) {
            buf[charPos--] = digits[-(i % radix)];
            i = i / radix;
        }
        
        buf[charPos] = digits[-i];
        if (negative) buf[--charPos] = '-';
        return new String(buf, charPos, (33 - charPos));
    }
    
    private boolean isValidName(String name) {        
        return REGEX_VALID_NAME.matcher(name).matches() && Arrays.binarySearch(RESERVED_NAMES, name) < 0;
    }    
    
    private int printSourceNumber(String source, int offset, StringBuffer sb) {
        double number = 0.0;
        char type = source.charAt(offset);
        ++offset;
        
        if (type == 'S') {
            if (sb != null) {
                int ival = source.charAt(offset);
                number = ival;
            }
            
            ++offset;
        } else if (type == 'J' || type == 'D') {
            if (sb != null) {
                long lbits;
                lbits = (long)source.charAt(offset) << 48;
                lbits |= (long)source.charAt(offset + 1) << 32;
                lbits |= (long)source.charAt(offset + 2) << 16;
                lbits |= (long)source.charAt(offset + 3);                
                number = type == 'J' ? lbits : Double.longBitsToDouble(lbits);
            }
            
            offset += 4;
        } else {
            // Bad source
            throw new RuntimeException();
        }
        
        if (sb != null) sb.append(ScriptRuntime.numberToString(number, 10));        
        return offset;
    }
}
