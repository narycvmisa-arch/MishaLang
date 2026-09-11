package mvm;

import java.util.ArrayList;
import java.util.List;

/**
 * MVM Bytecode для языка Misha (.mih/.mixa -> .mvm)
 * Стековая VM, как JVM но упрощенная.
 * Fixes: вложенные списки, строки с запятыми/экранированием, robust decode
 */
public class Bytecode {
    public enum Op {
        PUSH, POP, DUP,
        LOAD, STORE,
        ADD, SUB, MUL, DIV, MOD, POW, NEG,
        EQ, NE, GT, LT, GE, LE, AND, OR, NOT,
        JMP, CJMP, LABEL, CALL, RET,
        PRINT, PRINTLN, SLEEP, EXIT,
        NEW, GETFIELD, SETFIELD, INVOKE,
        NEWARRAY, ALOAD, ASTORE, ALEN,
        CONST, VAR
    }

    public static class Instr {
        public final Op op;
        public final Object arg;
        public final String comment;
        public Instr(Op op) { this(op, null, null); }
        public Instr(Op op, Object arg) { this(op, arg, null); }
        public Instr(Op op, Object arg, String comment) { this.op = op; this.arg = arg; this.comment = comment; }
        @Override public String toString() {
            return arg == null ? op.name() : op.name() + " " + arg + (comment != null ? " // " + comment : "");
        }
        public String encode() {
            if (arg == null) return op.name();
            if (arg instanceof String s) {
                return op.name() + " \"" + s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t") + "\"";
            }
            if (arg instanceof java.util.List) {
                java.util.List<?> l = (java.util.List<?>)arg;
                StringBuilder sb = new StringBuilder();
                sb.append("__LIST__:[");
                for(int i=0;i<l.size();i++){
                    Object o=l.get(i);
                    if(o instanceof java.util.List) {
                        // рекурсивно
                        Instr tmp = new Instr(Op.PUSH, o);
                        String enc = tmp.encode();
                        // enc = PUSH "__LIST__:..."
                        String inner = enc.substring(5).trim();
                        // inner = "\"__LIST__:[...]\""
                        inner = inner.substring(1, inner.length()-1).replace("\\n","\n").replace("\\\"","\"").replace("\\\\","\\");
                        // но проще: вложенный список уже как строка __LIST__:[...] без кавычек? Экранируем как строку
                        sb.append("\"").append(inner.replace("\\","\\\\").replace("\"","\\\"")).append("\"");
                    } else if(o instanceof String s) {
                        sb.append("\"").append(s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")).append("\"");
                    } else if(o==null) sb.append("null");
                    else sb.append(String.valueOf(o));
                    if(i<l.size()-1) sb.append(",");
                }
                sb.append("]");
                return op.name() + " \"" + sb.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n") + "\"";
            }
            return op.name() + " " + arg;
        }
        // парсинг списка с учетом вложенности и кавычек
        private static List<Object> parseListInner(String inner){
            List<Object> out=new ArrayList<>();
            if(inner.trim().isEmpty()) return out;
            int i=0, n=inner.length();
            StringBuilder cur=new StringBuilder();
            boolean inStr=false, esc=false;
            int bracketDepth=0;
            for(; i<n; i++){
                char c=inner.charAt(i);
                if(esc){ cur.append(c); esc=false; continue; }
                if(c=='\\'){ esc=true; cur.append(c); continue; }
                if(c=='\"'){ inStr=!inStr; cur.append(c); continue; }
                if(!inStr){
                    if(c=='[') bracketDepth++;
                    if(c==']') bracketDepth--;
                    if(c==',' && bracketDepth==0){
                        String tok=cur.toString().trim();
                        if(!tok.isEmpty()) out.add(parseListElement(tok));
                        cur.setLength(0);
                        continue;
                    }
                }
                cur.append(c);
            }
            String tok=cur.toString().trim();
            if(!tok.isEmpty()) out.add(parseListElement(tok));
            return out;
        }
        private static Object parseListElement(String tok){
            tok=tok.trim();
            if(tok.startsWith("\"") && tok.endsWith("\"") && tok.length()>=2){
                String s=tok.substring(1, tok.length()-1).replace("\\n","\n").replace("\\\"","\"").replace("\\\\","\\").replace("\\t","\t").replace("\\r","\r");
                if(s.startsWith("__LIST__:[")){
                    // вложенный список хранится как строка "__LIST__:[...]" внутри кавычек
                    String sub=s.substring("__LIST__:[".length(), s.length()-1);
                    return parseListInner(sub);
                }
                return s;
            }
            if(tok.equals("null")) return null;
            if(tok.equals("true")) return true;
            if(tok.equals("false")) return false;
            // попробуем число
            try{ return Integer.parseInt(tok); }catch(Exception e){}
            try{ return Long.parseLong(tok.replaceAll("[lL]$","")); }catch(Exception e){}
            try{ return Double.parseDouble(tok.replaceAll("[fFdD]$","")); }catch(Exception e){}
            // вложенный список без кавычек? редко
            if(tok.startsWith("__LIST__:[")){
                String sub=tok.substring("__LIST__:[".length(), tok.length()-1);
                return parseListInner(sub);
            }
            return tok;
        }

        public static Instr decode(String line) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) return null;
            if (line.matches("^\\d+:\\s*.*")) line = line.replaceFirst("^\\d+:\\s*", "");
            if (line.isEmpty() || line.startsWith("//")) return null;
            String[] parts = line.split("\\s+", 2);
            try {
                Op op = Op.valueOf(parts[0]);
                Object arg = null;
                if (parts.length == 2) {
                    String a = parts[1].trim();
                    if (a.startsWith("\"") && a.endsWith("\"")) {
                        String inner = a.substring(1, a.length()-1).replace("\\n","\n").replace("\\r","\r").replace("\\t","\t").replace("\\\"","\"").replace("\\\\","\\");
                        if (inner.startsWith("__LIST__:[")){
                            String listInner = inner.substring("__LIST__:[".length(), inner.length()-1);
                            arg = parseListInner(listInner);
                        } else {
                            arg = inner;
                        }
                    } else {
                        if(a.equals("true")) arg = true;
                        else if(a.equals("false")) arg = false;
                        else if(a.equals("null")) arg = null;
                        else try { arg = Integer.parseInt(a); } catch(Exception e1){
                            try { 
                                String t=a.replaceAll("[lL]$","");
                                if(t.contains(".")||t.toLowerCase().contains("e")) arg = Double.parseDouble(t.replaceAll("[fFdD]$",""));
                                else arg = Long.parseLong(t);
                                // сузим до Integer если влезает
                                if(arg instanceof Long l && l>=Integer.MIN_VALUE && l<=Integer.MAX_VALUE) arg=l.intValue();
                            } catch(Exception e2){ arg = a; }
                        }
                    }
                }
                return new Instr(op, arg);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    public static List<Instr> parseMvmText(String text) {
        List<Instr> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            Instr i = Instr.decode(line);
            if (i != null) out.add(i);
        }
        return out;
    }

    public static String disasm(List<Instr> code) {
        StringBuilder sb = new StringBuilder();
        sb.append("// MVM bytecode v1 (Misha VM)\n");
        sb.append("// .mih/.mixa -> .mvm\n");
        for (int i=0;i<code.size();i++) sb.append(String.format("%04d: %s\n", i, code.get(i).encode()));
        return sb.toString();
    }
}
