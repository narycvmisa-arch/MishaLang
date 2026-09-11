package mvm;

import java.util.*;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import javax.swing.JFrame;
import javax.swing.JPanel;

/**
 * MVM - Misha Virtual Machine
 * Стековая VM для языка Миша (.mih/.mixa)
 * Запуск: java mvm.MVM program.mvm
 * Fixes: STORE без дубля в globals, JMP проверка метки, CALL поиск в locals+globals, NEG, расширенные builtins
 */
public class MVM {
    private final List<Bytecode.Instr> code;
    private final Map<String,Integer> labels = new HashMap<>();
    private final Deque<Object> stack = new LinkedList<>();
    private final Map<String,Object> globals = new HashMap<>();
    private final Map<String,Object> locals = new HashMap<>();
    private final Deque<Integer> callStack = new ArrayDeque<>();
    private final Deque<Map<String,Object>> frameStack = new ArrayDeque<>();
    private int ip = 0;
    private boolean halted = false;
    private static final Random RND = new Random();
    private static java.util.Map<String,Object> lastWindow2D = null;
    private static double cubeAngle = 0;
    private static long lastSwapMillis = System.currentTimeMillis();
    private static int realFps = 30;

    public MVM(List<Bytecode.Instr> code) {
        this.code = code;
        for (int i=0;i<code.size();i++) {
            var ins = code.get(i);
            if (ins.op == Bytecode.Op.LABEL && ins.arg instanceof String name) {
                labels.put(name, i);
            }
        }
    }

    public void run() {
        int steps=0;
        while (ip < code.size() && !halted) {
            if(++steps > 500000){
                System.err.println("[MVM] Превышен лимит 500k шагов - возможно бесконечный цикл, остановлено. IP="+ip+" OP="+code.get(ip));
                break;
            }
            if(stack.size() > 10000){
                System.err.println("[MVM] Stack >10k - остановлено, возможно бесконечный PUSH");
                break;
            }
            var ins = code.get(ip);
            if (ins.op == Bytecode.Op.LABEL) { ip++; continue; }
            exec(ins);
            ip++;
        }
    }

    private void exec(Bytecode.Instr ins) {
        switch (ins.op) {
            case PUSH:
            case CONST:
                stack.push(ins.arg);
                break;
            case POP:
                if(!stack.isEmpty()) stack.pop();
                else System.err.println("[MVM] WARN POP empty at ip "+ip);
                break;
            case DUP:
                if(!stack.isEmpty()) stack.push(stack.peek());
                else System.err.println("[MVM] WARN DUP empty at ip "+ip);
                break;
            case VAR:
            case LOAD: {
                String name = String.valueOf(ins.arg);
                // поддержка win.w / win.h / cam.pos  -> field access
                if(name.contains(".")){
                    String[] parts=name.split("\\.",2);
                    Object obj = locals.containsKey(parts[0]) ? locals.get(parts[0]) : globals.get(parts[0]);
                    if(obj instanceof Map m){
                        Object fv=m.get(parts[1]);
                        stack.push(fv!=null?fv:0);
                    } else if(obj!=null){
                        // reflection fallback: try get field via toString?
                        stack.push(0);
                    } else stack.push(0);
                    break;
                }
                Object v = locals.containsKey(name) ? locals.get(name) : globals.get(name);
                if(v==null && !locals.containsKey(name) && !globals.containsKey(name)){
                    v = 0;
                }
                stack.push(v);
                break;
            }
            case STORE: {
                String name = String.valueOf(ins.arg);
                Object v = stack.isEmpty() ? null : stack.pop();
                if(name.contains(".")){
                    String[] parts=name.split("\\.",2);
                    Object obj = locals.containsKey(parts[0]) ? locals.get(parts[0]) : globals.get(parts[0]);
                    if(obj instanceof java.util.Map m){
                        m.put(parts[1], v);
                    } else {
                        System.err.println("[MVM] WARN STORE field "+name+" no map at ip "+ip);
                    }
                    break;
                }
                if (locals.containsKey(name) || !globals.containsKey(name)) locals.put(name, v);
                else globals.put(name, v);
                break;
            }
            case ADD:
                binOp(new Bin() { public Object apply(Object a, Object b) {
                    if (a instanceof String || b instanceof String) return String.valueOf(a)+String.valueOf(b);
                    if (a instanceof Double || b instanceof Double || a instanceof Float || b instanceof Float) return toDouble(a)+toDouble(b);
                    return toLong(a)+toLong(b);
                }}); break;
            case SUB:
                binOp(new Bin() { public Object apply(Object a, Object b) {
                    double d = toDouble(a)-toDouble(b);
                    long l = (long)d;
                    return d==l ? l : d;
                }}); break;
            case MUL:
                binOp(new Bin() { public Object apply(Object a, Object b) {
                    double d = toDouble(a)*toDouble(b);
                    // если оба целые и результат целый — вернем long
                    if(a instanceof Integer && b instanceof Integer && d==(long)d) return (long)d;
                    if(a instanceof Long && b instanceof Long && d==(long)d) return (long)d;
                    return d;
                }}); break;
            case DIV:
                binOp(new Bin() { public Object apply(Object a, Object b) {
                    double db = toDouble(b);
                    if(db==0){ System.err.println("[MVM] WARN DIV by 0 at ip "+ip); return Double.NaN; }
                    return toDouble(a)/db;
                }}); break;
            case MOD:
                binOp(new Bin() { public Object apply(Object a, Object b) { return toLong(a)%toLong(b); }}); break;
            case POW:
                binOp(new Bin() { public Object apply(Object a, Object b) { return Math.pow(toDouble(a), toDouble(b)); }}); break;
            case NEG: {
                Object a = stack.isEmpty()?0:stack.pop();
                if(a instanceof Double || a instanceof Float) stack.push(-toDouble(a));
                else stack.push(-toLong(a));
                break;
            }
            case EQ:
                binOp(new Bin() { public Object apply(Object a, Object b) { return Objects.equals(a,b); }}); break;
            case NE:
                binOp(new Bin() { public Object apply(Object a, Object b) { return !Objects.equals(a,b); }}); break;
            case GT:
                binOp(new Bin() { public Object apply(Object a, Object b) { return compare(a,b)>0; }}); break;
            case LT:
                binOp(new Bin() { public Object apply(Object a, Object b) { return compare(a,b)<0; }}); break;
            case GE:
                binOp(new Bin() { public Object apply(Object a, Object b) { return compare(a,b)>=0; }}); break;
            case LE:
                binOp(new Bin() { public Object apply(Object a, Object b) { return compare(a,b)<=0; }}); break;
            case AND:
                binOp(new Bin() { public Object apply(Object a, Object b) { return toBool(a) && toBool(b); }}); break;
            case OR:
                binOp(new Bin() { public Object apply(Object a, Object b) { return toBool(a) || toBool(b); }}); break;
            case NOT: {
                Object a=stack.isEmpty()?false:stack.pop(); stack.push(!toBool(a)); break;
            }
            case JMP: {
                String lbl=String.valueOf(ins.arg);
                Integer t = labels.get(lbl);
                if(t==null){ System.err.println("[MVM] ERR unknown label "+lbl+" at ip "+ip); halted=true; break; }
                ip = t; break;
            }
            case CJMP: {
                String lbl=String.valueOf(ins.arg);
                Object cond = stack.isEmpty()? false : stack.pop();
                if (!toBool(cond)){
                    Integer t = labels.get(lbl);
                    if(t==null){ System.err.println("[MVM] ERR unknown label "+lbl+" at ip "+ip); halted=true; break; }
                    ip = t;
                }
                break;
            }
            case PRINT: {
                Object v = stack.isEmpty()? "null" : stack.pop();
                System.out.print(v);
                break;
            }
            case PRINTLN: {
                Object v = stack.isEmpty()? "null" : stack.pop();
                System.out.println(v);
                break;
            }
            case SLEEP: {
                Object v = stack.isEmpty()? 0 : stack.pop();
                try { Thread.sleep(toLong(v)); } catch(Exception ignored){}
                break;
            }
            case CALL: {
                String fn = String.valueOf(ins.arg);
                Object maybeLabel = locals.containsKey(fn) ? locals.get(fn) : globals.get(fn);
                if(maybeLabel instanceof String lbl && labels.containsKey(lbl)){
                    frameStack.push(new HashMap<>(locals));
                    callStack.push(ip+1);
                    ip = labels.get(lbl);
                    break;
                }
                switch(fn){
                    case "len":
                    case "length":
                    case "strlen": {
                        Object a = stack.isEmpty()? null : stack.pop();
                        int sz = 0;
                        if(a instanceof java.util.Collection c) sz=c.size();
                        else if(a instanceof Object[] arr) sz=arr.length;
                        else if(a instanceof String s) sz=s.length();
                        else if(a instanceof java.util.Map m) sz=m.size();
                        else if(a instanceof byte[] b) sz=b.length;
                        else if(a instanceof int[] ib) sz=ib.length;
                        else sz=0;
                        stack.push(sz);
                        break;
                    }
                    case "List": stack.push(new java.util.ArrayList<>()); break;
                    case "Map": stack.push(new java.util.HashMap<>()); break;
                    case "Set": stack.push(new java.util.HashSet<>()); break;
                    case "Box": {
                        java.util.Map<String,Object> box = new java.util.HashMap<>();
                        box.put("__type","Box");
                        stack.push(box);
                        break;
                    }
                    case "lower": {
                        Object a = stack.isEmpty()? "" : stack.pop();
                        stack.push(String.valueOf(a).toLowerCase());
                        break;
                    }
                    case "upper": {
                        Object a = stack.isEmpty()? "" : stack.pop();
                        stack.push(String.valueOf(a).toUpperCase());
                        break;
                    }
                    case "trim": {
                        Object a = stack.isEmpty()? "" : stack.pop();
                        stack.push(String.valueOf(a).trim());
                        break;
                    }
                    case "capitalize": {
                        Object a = stack.isEmpty()? "" : stack.pop();
                        String s = String.valueOf(a);
                        if(s.isEmpty()) stack.push(s);
                        else stack.push(s.substring(0,1).toUpperCase()+s.substring(1));
                        break;
                    }
                    case "replace": {
                        Object repl = stack.isEmpty()? "" : stack.pop();
                        Object target = stack.isEmpty()? "" : stack.pop();
                        Object src = stack.isEmpty()? "" : stack.pop();
                        stack.push(String.valueOf(src).replace(String.valueOf(target), String.valueOf(repl)));
                        break;
                    }
                    case "contains": {
                        Object sub = stack.isEmpty()? "" : stack.pop();
                        Object src = stack.isEmpty()? "" : stack.pop();
                        if(src instanceof java.util.Collection c) stack.push(c.contains(sub));
                        else if(src instanceof java.util.Map m) stack.push(m.containsKey(sub));
                        else stack.push(String.valueOf(src).contains(String.valueOf(sub)));
                        break;
                    }
                    case "split": {
                        Object delim = stack.isEmpty()? "" : stack.pop();
                        Object src = stack.isEmpty()? "" : stack.pop();
                        String[] parts = String.valueOf(src).split(java.util.regex.Pattern.quote(String.valueOf(delim)));
                        stack.push(new java.util.ArrayList<>(java.util.Arrays.asList(parts)));
                        break;
                    }
                    case "substring":
                    case "substr": {
                        // поддержка и функционального substr(txt,1,3) и метода txt.substring(1,3)
                        if(stack.size()>=3){
                            Object b = stack.pop();
                            Object a = stack.pop();
                            Object src = stack.pop();
                            try{
                                String s = String.valueOf(src);
                                int ai=(int)toLong(a), bi=(int)toLong(b);
                                if(ai<0) ai=0; if(bi>s.length()) bi=s.length();
                                stack.push(s.substring(ai, bi));
                            }catch(Exception e){ stack.push(""); }
                        } else if(stack.size()>=2){
                            Object a = stack.pop();
                            Object src = stack.pop();
                            try{
                                String s = String.valueOf(src);
                                int ai=(int)toLong(a);
                                stack.push(s.substring(ai));
                            }catch(Exception e){ stack.push(""); }
                        } else {
                            stack.push("");
                        }
                        break;
                    }
                    case "startsWith": {
                        Object pref = stack.isEmpty()? "" : stack.pop();
                        Object src = stack.isEmpty()? "" : stack.pop();
                        stack.push(String.valueOf(src).startsWith(String.valueOf(pref)));
                        break;
                    }
                    case "endsWith": {
                        Object suf = stack.isEmpty()? "" : stack.pop();
                        Object src = stack.isEmpty()? "" : stack.pop();
                        stack.push(String.valueOf(src).endsWith(String.valueOf(suf)));
                        break;
                    }
                    case "charAt": {
                        Object idx = stack.isEmpty()? 0 : stack.pop();
                        Object src = stack.isEmpty()? "" : stack.pop();
                        try{ stack.push(String.valueOf(String.valueOf(src).charAt((int)toLong(idx)))); }catch(Exception e){ stack.push(""); }
                        break;
                    }
                    case "index":
                    case "indexOf": {
                        Object sub = stack.isEmpty()? "" : stack.pop();
                        Object src = stack.isEmpty()? "" : stack.pop();
                        stack.push(String.valueOf(src).indexOf(String.valueOf(sub)));
                        break;
                    }
                    case "isEmpty": {
                        Object a = stack.isEmpty()? null : stack.pop();
                        if(a instanceof String s) stack.push(s.isEmpty());
                        else if(a instanceof java.util.Collection c) stack.push(c.isEmpty());
                        else if(a instanceof java.util.Map m) stack.push(m.isEmpty());
                        else if(a instanceof Object[] arr) stack.push(arr.length==0);
                        else stack.push(a==null);
                        break;
                    }
                    case "repeat": {
                        Object n = stack.isEmpty()? 1 : stack.pop();
                        Object src = stack.isEmpty()? "" : stack.pop();
                        try{ stack.push(String.valueOf(src).repeat((int)toLong(n))); }catch(Exception e){ stack.push(String.valueOf(src)); }
                        break;
                    }
                    case "reverse": {
                        Object a = stack.isEmpty()? null : stack.pop();
                        if(a instanceof java.util.List l){
                            java.util.List c=new java.util.ArrayList<>(l); Collections.reverse(c); stack.push(c);
                        } else if(a instanceof String s){
                            stack.push(new StringBuilder(s).reverse().toString());
                        } else {
                            stack.push(a);
                        }
                        break;
                    }
                    case "getAt": {
                        Object idx = stack.isEmpty()? 0 : stack.pop();
                        Object arr = stack.isEmpty()? null : stack.pop();
                        if(arr instanceof java.util.List l){
                            int i=(int)toLong(idx);
                            if(i>=0 && i<l.size()) stack.push(l.get(i));
                            else stack.push(null);
                        } else if(arr instanceof Object[] a){
                            int i=(int)toLong(idx);
                            if(i>=0 && i<a.length) stack.push(a[i]); else stack.push(null);
                        } else if(arr instanceof String s){
                            int i=(int)toLong(idx);
                            if(i>=0 && i<s.length()) stack.push(String.valueOf(s.charAt(i))); else stack.push("");
                        } else if(arr instanceof byte[] b){
                            int i=(int)toLong(idx);
                            if(i>=0 && i<b.length) stack.push((int)b[i]); else stack.push(0);
                        } else if(arr instanceof int[] ip){
                            int i=(int)toLong(idx);
                            if(i>=0 && i<ip.length) stack.push(ip[i]); else stack.push(0);
                        } else stack.push(null);
                        break;
                    }
                    case "setAt": {
                        Object val = stack.isEmpty()? null : stack.pop();
                        Object idx = stack.isEmpty()? 0 : stack.pop();
                        Object arr = stack.isEmpty()? null : stack.pop();
                        if(arr instanceof java.util.List l){
                            int i=(int)toLong(idx);
                            if(i>=0 && i<l.size()) l.set(i, val);
                            stack.push(l);
                        } else if(arr instanceof int[] ip){
                            int i=(int)toLong(idx);
                            if(i>=0 && i<ip.length) {
                                ip[i]=(int)toLong(val);
                                // синхронизация пикселя с BufferedImage для собственного рендеринга
                                if(lastWindow2D!=null && lastWindow2D.get("pixels")==ip && lastWindow2D.get("__image") instanceof BufferedImage img){
                                    int wi=(int)toLong(lastWindow2D.get("w"));
                                    int sc=(int)toLong(lastWindow2D.getOrDefault("__scale",16));
                                    int x=i % wi, y=i / wi;
                                    int rgb=(int)toLong(val);
                                    if(rgb==0) rgb=0x202035;
                                    synchronized(img){
                                        Graphics2D g=img.createGraphics();
                                        g.setColor(new Color(rgb));
                                        g.fillRect(x*sc, y*sc, sc, sc);
                                        g.dispose();
                                    }
                                }
                            }
                            stack.push(ip);
                        } else if(arr instanceof byte[] bp){
                            int i=(int)toLong(idx);
                            if(i>=0 && i<bp.length) bp[i]=(byte)toLong(val);
                            stack.push(bp);
                        } else stack.push(arr);
                        break;
                    }
                    case "get": {
                        Object obj = stack.isEmpty()? null : stack.pop();
                        if(obj instanceof java.util.Map m) stack.push(m.getOrDefault("content", m.get("value")));
                        else if(obj instanceof java.util.List l && !l.isEmpty()) stack.push(l.get(0));
                        else stack.push(obj);
                        break;
                    }
                    case "set": {
                        Object val = stack.isEmpty()? null : stack.pop();
                        Object obj = stack.isEmpty()? null : stack.pop();
                        if(obj instanceof java.util.Map m){ m.put("content", val); m.put("value", val); stack.push(obj); }
                        else stack.push(val);
                        break;
                    }
                    case "add": {
                        Object val = stack.isEmpty()? null : stack.pop();
                        Object obj = stack.isEmpty()? null : stack.pop();
                        if(obj instanceof java.util.Collection c){ c.add(val); stack.push(c); }
                        else stack.push(val);
                        break;
                    }
                    case "put": {
                        Object val = stack.isEmpty()? null : stack.pop();
                        Object key = stack.isEmpty()? null : stack.pop();
                        Object obj = stack.isEmpty()? null : stack.pop();
                        if(obj instanceof java.util.Map m){ m.put(key, val); stack.push(m); }
                        else stack.push(obj);
                        break;
                    }
                    case "concat": {
                        Object b = stack.isEmpty()? "" : stack.pop();
                        Object a = stack.isEmpty()? "" : stack.pop();
                        stack.push(String.valueOf(a)+String.valueOf(b));
                        break;
                    }
                    case "first": {
                        Object a = stack.isEmpty()? null : stack.pop();
                        if(a instanceof java.util.List l) stack.push(l.isEmpty()?null:l.get(0));
                        else if(a instanceof String s) stack.push(s.isEmpty()?"":String.valueOf(s.charAt(0)));
                        else stack.push(a);
                        break;
                    }
                    case "last": {
                        Object a = stack.isEmpty()? null : stack.pop();
                        if(a instanceof java.util.List l) stack.push(l.isEmpty()?null:l.get(l.size()-1));
                        else if(a instanceof String s) stack.push(s.isEmpty()?"":String.valueOf(s.charAt(s.length()-1)));
                        else stack.push(a);
                        break;
                    }
                    case "append": {
                        Object val = stack.isEmpty()? null : stack.pop();
                        Object lst = stack.isEmpty()? null : stack.pop();
                        if(lst instanceof java.util.List l){ java.util.List n=new java.util.ArrayList<>(l); n.add(val); stack.push(n); }
                        else if(lst instanceof java.util.Collection c){ c.add(val); stack.push(c); }
                        else stack.push(val);
                        break;
                    }
                    case "prepend": {
                        Object val = stack.isEmpty()? null : stack.pop();
                        Object lst = stack.isEmpty()? null : stack.pop();
                        if(lst instanceof java.util.List l){ java.util.List n=new java.util.ArrayList<>(); n.add(val); n.addAll(l); stack.push(n); }
                        else stack.push(val);
                        break;
                    }
                    case "remove": {
                        Object val = stack.isEmpty()? null : stack.pop();
                        Object lst = stack.isEmpty()? null : stack.pop();
                        if(lst instanceof java.util.List l){ java.util.List n=new java.util.ArrayList<>(l); n.remove(val); stack.push(n); }
                        else stack.push(lst);
                        break;
                    }
                    case "sort": {
                        Object a = stack.isEmpty()? null : stack.pop();
                        if(a instanceof java.util.List l){ java.util.List n=new java.util.ArrayList<>(l); try{ Collections.sort((List) n); }catch(Exception ignored){} stack.push(n); }
                        else stack.push(a);
                        break;
                    }
                    case "unique": {
                        Object a = stack.isEmpty()? null : stack.pop();
                        if(a instanceof java.util.List l){ stack.push(new java.util.ArrayList<>(new java.util.LinkedHashSet<>(l))); }
                        else stack.push(a);
                        break;
                    }
                    case "and": {
                        Object b = stack.isEmpty()? false : stack.pop();
                        Object a = stack.isEmpty()? false : stack.pop();
                        stack.push(toBool(a) && toBool(b));
                        break;
                    }
                    case "or": {
                        Object b = stack.isEmpty()? false : stack.pop();
                        Object a = stack.isEmpty()? false : stack.pop();
                        stack.push(toBool(a) || toBool(b));
                        break;
                    }
                    case "not": {
                        Object a = stack.isEmpty()? false : stack.pop();
                        stack.push(!toBool(a));
                        break;
                    }
                    case "xor": {
                        Object b = stack.isEmpty()? false : stack.pop();
                        Object a = stack.isEmpty()? false : stack.pop();
                        stack.push(toBool(a) ^ toBool(b));
                        break;
                    }
                    case "round": {
                        Object a = stack.isEmpty()? 0 : stack.pop();
                        stack.push(Math.round(toDouble(a)));
                        break;
                    }
                    case "floor": {
                        Object a = stack.isEmpty()? 0 : stack.pop();
                        stack.push((long)Math.floor(toDouble(a)));
                        break;
                    }
                    case "ceil": {
                        Object a = stack.isEmpty()? 0 : stack.pop();
                        stack.push((long)Math.ceil(toDouble(a)));
                        break;
                    }
                    case "isInt": {
                        Object a = stack.isEmpty()? null : stack.pop();
                        stack.push(a instanceof Integer || a instanceof Long);
                        break;
                    }
                    case "isString": {
                        Object a = stack.isEmpty()? null : stack.pop();
                        stack.push(a instanceof String);
                        break;
                    }
                    case "isArray": {
                        Object a = stack.isEmpty()? null : stack.pop();
                        stack.push(a instanceof java.util.List || a instanceof Object[] || a instanceof byte[]);
                        break;
                    }
                    case "isNull": {
                        Object a = stack.isEmpty()? null : stack.pop();
                        stack.push(a==null || (a instanceof String && a.equals("null")));
                        break;
                    }
                    case "typeOf":
                    case "typeof":
                    case "classname": {
                        Object a = stack.isEmpty()? null : stack.pop();
                        if(a==null) stack.push("null");
                        else if(a instanceof String) stack.push("String");
                        else if(a instanceof Integer) stack.push("int");
                        else if(a instanceof Long) stack.push("long");
                        else if(a instanceof Double) stack.push("double");
                        else if(a instanceof Boolean) stack.push("bool");
                        else if(a instanceof java.util.List) stack.push("List");
                        else if(a instanceof java.util.Map) stack.push(a instanceof java.util.Map && ((java.util.Map)a).containsKey("__type") ? String.valueOf(((java.util.Map)a).get("__type")) : "Map");
                        else stack.push(a.getClass().getSimpleName());
                        break;
                    }
                    case "toInt": {
                        Object a = stack.isEmpty()? "0" : stack.pop();
                        try{ stack.push(Integer.parseInt(String.valueOf(a).trim())); }catch(Exception e){ try{ stack.push((int)toLong(a)); }catch(Exception e2){ stack.push(0);} }
                        break;
                    }
                    case "toFloat":
                    case "toDouble": {
                        Object a = stack.isEmpty()? "0" : stack.pop();
                        try{ stack.push(Double.parseDouble(String.valueOf(a).trim())); }catch(Exception e){ stack.push(toDouble(a)); }
                        break;
                    }
                    case "toString": {
                        Object a = stack.isEmpty()? null : stack.pop();
                        stack.push(String.valueOf(a));
                        break;
                    }
                    case "toArray": {
                        Object a = stack.isEmpty()? "" : stack.pop();
                        String s=String.valueOf(a);
                        String[] parts=s.split(",");
                        stack.push(new java.util.ArrayList<>(java.util.Arrays.asList(parts)));
                        break;
                    }
                    case "randint": {
                        if(stack.size()>=2){
                            Object b = stack.pop(); Object a = stack.pop();
                            if(a instanceof Number && b instanceof Number){
                                int min=(int)toLong(a), max=(int)toLong(b);
                                if(min>max){int t=min; min=max; max=t;}
                                stack.push(min + RND.nextInt(max-min+1));
                                break;
                            } else {
                                stack.push(a);
                                int max=(int)toLong(b);
                                stack.push(RND.nextInt(max+1));
                                break;
                            }
                        }
                        Object a = stack.isEmpty()? 0 : stack.pop();
                        int max = (int)toLong(a);
                        if(max<0) max=0;
                        stack.push(RND.nextInt(max+1));
                        break;
                    }
                    case "randfloat": {
                        if(stack.isEmpty()){ stack.push(RND.nextDouble()); }
                        else if(stack.size()>=2){
                            Object b=stack.pop(), a=stack.pop();
                            if(a instanceof Number && b instanceof Number){
                                double da=toDouble(a), db=toDouble(b);
                                stack.push(da + RND.nextDouble()*(db-da));
                            } else {
                                stack.push(a); stack.push(RND.nextDouble()*toDouble(b));
                            }
                        } else {
                            Object a=stack.pop();
                            stack.push(RND.nextDouble()*toDouble(a));
                        }
                        break;
                    }
                    case "randbool": stack.push(RND.nextBoolean()); break;
                    case "randchar": stack.push(String.valueOf((char)('a'+RND.nextInt(26)))); break;
                    case "randstring": {
                        Object a=stack.isEmpty()?5:stack.pop();
                        int n=(int)toLong(a); StringBuilder sb=new StringBuilder();
                        for(int i=0;i<n;i++) sb.append((char)('a'+RND.nextInt(26)));
                        stack.push(sb.toString()); break;
                    }
                    case "randrange": {
                        Object a=stack.isEmpty()?0:stack.pop();
                        if(stack.size()>=1 && a instanceof Number && stack.peek() instanceof Number){
                            Object b=a; a=stack.pop();
                            int ai=(int)toLong(a), bi=(int)toLong(b);
                            if(ai>bi){int t=ai; ai=bi; bi=t;}
                            stack.push(ai + RND.nextInt(bi-ai==0?1:bi-ai));
                        } else {
                            int max=(int)toLong(a);
                            if(max<=0) stack.push(0); else stack.push(RND.nextInt(max));
                        }
                        break;
                    }
                    case "choice": {
                        Object a = stack.isEmpty()? null : stack.pop();
                        if(a instanceof java.util.List l && !l.isEmpty()) stack.push(l.get(RND.nextInt(l.size())));
                        else if(a instanceof String s && !s.isEmpty()) stack.push(String.valueOf(s.charAt(RND.nextInt(s.length()))));
                        else stack.push(a);
                        break;
                    }
                    case "choices": {
                        Object a=stack.isEmpty()?null:stack.pop();
                        Object b=stack.isEmpty()?1:stack.pop();
                        int n=(int)toLong(a);
                        if(b instanceof java.util.List l){
                            java.util.List res=new java.util.ArrayList<>();
                            for(int i=0;i<n;i++) res.add(l.get(RND.nextInt(l.size())));
                            stack.push(res);
                        } else {
                            stack.push(b);
                        }
                        break;
                    }
                    case "shuffle": {
                        Object a=stack.isEmpty()?null:stack.pop();
                        if(a instanceof java.util.List l){
                            java.util.List c=new java.util.ArrayList<>(l);
                            java.util.Collections.shuffle(c, RND);
                            stack.push(c);
                        } else stack.push(a);
                        break;
                    }
                    case "alloc": {
                        Object a = stack.isEmpty()? 0 : stack.pop();
                        int sz = (int)toLong(a);
                        if(sz<0) sz=0;
                        stack.push(new byte[sz]);
                        break;
                    }
                    case "allocArray": {
                        Object a = stack.isEmpty()? 0 : stack.pop();
                        int sz = (int)toLong(a);
                        if(sz<0) sz=0;
                        stack.push(new Object[sz]);
                        break;
                    }
                    case "realloc": {
                        Object n = stack.isEmpty()?0:stack.pop();
                        Object o = stack.isEmpty()?null:stack.pop();
                        int sz=(int)toLong(n);
                        stack.push(new byte[sz]);
                        break;
                    }
                    case "free":
                    case "freeArray":
                    case "gc": {
                        if(fn.equals("free")||fn.equals("freeArray")){
                            if(!stack.isEmpty()) stack.pop();
                        } else {
                            if(!stack.isEmpty()) stack.pop();
                            System.gc();
                        }
                        stack.push(0);
                        break;
                    }
                    case "meminfo": {
                        stack.push("meminfo: max="+java.lang.Runtime.getRuntime().maxMemory()+" free="+java.lang.Runtime.getRuntime().freeMemory());
                        break;
                    }
                    case "memused": stack.push(java.lang.Runtime.getRuntime().totalMemory()-java.lang.Runtime.getRuntime().freeMemory()); break;
                    case "memfree": stack.push(java.lang.Runtime.getRuntime().freeMemory()); break;
                    case "memtotal": stack.push(java.lang.Runtime.getRuntime().totalMemory()); break;
                    case "read": {
                        Object a = stack.isEmpty()? "" : stack.pop();
                        try{ stack.push(java.nio.file.Files.readString(java.nio.file.Path.of(String.valueOf(a)))); }catch(Exception e){ stack.push(""); }
                        break;
                    }
                    case "write": {
                        Object b = stack.isEmpty()? "" : stack.pop();
                        Object a = stack.isEmpty()? "" : stack.pop();
                        try{ java.nio.file.Files.writeString(java.nio.file.Path.of(String.valueOf(a)), String.valueOf(b)); }catch(Exception ignored){}
                        stack.push(0);
                        break;
                    }
                    case "exists": {
                        Object a = stack.isEmpty()? "" : stack.pop();
                        stack.push(java.nio.file.Files.exists(java.nio.file.Path.of(String.valueOf(a))));
                        break;
                    }
                    case "appendFile": {
                        Object b = stack.isEmpty()? "" : stack.pop();
                        Object a = stack.isEmpty()? "" : stack.pop();
                        try{ java.nio.file.Files.writeString(java.nio.file.Path.of(String.valueOf(a)), String.valueOf(b), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); }catch(Exception ignored){}
                        stack.push(0); break;
                    }
                    case "delete": {
                        Object a = stack.isEmpty()? "" : stack.pop();
                        try{ stack.push(java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(String.valueOf(a)))); }catch(Exception e){ stack.push(false); }
                        break;
                    }
                    case "listFiles": {
                        Object a = stack.isEmpty()? "" : stack.pop();
                        try{ stack.push(java.nio.file.Files.list(java.nio.file.Path.of(String.valueOf(a))).map(p->p.getFileName().toString()).collect(java.util.stream.Collectors.toList())); }catch(Exception e){ stack.push(new java.util.ArrayList<>()); }
                        break;
                    }
                    case "mkdir": {
                        Object a = stack.isEmpty()? "" : stack.pop();
                        try{ java.nio.file.Files.createDirectories(java.nio.file.Path.of(String.valueOf(a))); }catch(Exception ignored){}
                        stack.push(0); break;
                    }
                    case "fetch":
                    case "httpGet": {
                        Object a = stack.isEmpty()? "" : stack.pop();
                        try{ var c=java.net.http.HttpClient.newHttpClient(); var r=java.net.http.HttpRequest.newBuilder().uri(java.net.URI.create(String.valueOf(a))).GET().build(); stack.push(c.send(r, java.net.http.HttpResponse.BodyHandlers.ofString()).body()); }catch(Exception e){ stack.push("ERR:"+e.getMessage()); }
                        break;
                    }
                    case "httpPost": {
                        Object b = stack.isEmpty()? "" : stack.pop();
                        Object a = stack.isEmpty()? "" : stack.pop();
                        try{ var c=java.net.http.HttpClient.newHttpClient(); var r=java.net.http.HttpRequest.newBuilder().uri(java.net.URI.create(String.valueOf(a))).POST(java.net.http.HttpRequest.BodyPublishers.ofString(String.valueOf(b))).header("Content-Type","application/json").build(); stack.push(c.send(r, java.net.http.HttpResponse.BodyHandlers.ofString()).body()); }catch(Exception e){ stack.push("ERR:"+e.getMessage()); }
                        break;
                    }
                    case "sleep": {
                        Object a = stack.isEmpty()? 0 : stack.pop();
                        try{ Thread.sleep(toLong(a)); }catch(Exception ignored){}
                        stack.push(0); break;
                    }
                    case "now": stack.push(new Date().toString()); break;
                    case "date": stack.push(java.time.LocalDate.now().toString()); break;
                    case "time": stack.push(java.time.LocalTime.now().toString()); break;
                    case "millis":
                    case "ticks":
                    case "getTicks": stack.push(System.currentTimeMillis()); break;
                    // 2D/3D рендер из libs/MathD2, MathD3 — отдельное окно
                    case "createWindow2D":
                    case "Window2D": {
                        Object h = stack.isEmpty()? 0 : stack.pop();
                        Object w = stack.isEmpty()? 0 : stack.pop();
                        int wi=(int)toLong(w), hi=(int)toLong(h);
                        if(wi<=0) wi=40; if(hi<=0) hi=12;
                        final int fwi=wi, fhi=hi;
                        java.util.Map<String,Object> win=new java.util.HashMap<>();
                        win.put("__type","Window2D");
                        win.put("w", fwi); win.put("h", fhi);
                        int[] pix=new int[fwi*fhi];
                        win.put("pixels", pix);
                        win.put("shouldClose", false);
                        lastWindow2D=win;
                        int scale=16;
                        win.put("__scale", scale);
                        if(!GraphicsEnvironment.isHeadless()){
                            try{
                                BufferedImage img=new BufferedImage(fwi*scale, fhi*scale, BufferedImage.TYPE_INT_ARGB);
                                win.put("__image", img);
                                Graphics2D gg=img.createGraphics();
                                gg.setColor(new Color(10,10,20));
                                gg.fillRect(0,0,fwi*scale,fhi*scale);
                                gg.dispose();
                                final BufferedImage fImg = img;
                                final java.util.Map<String,Object> fWin = win;
                                final int ffwi = fwi, ffhi = fhi;
                                final int fScale = scale;
                                try{
                                    javax.swing.SwingUtilities.invokeAndWait(() -> {
                                        JPanel panel=new JPanel(){
                                            protected void paintComponent(Graphics g){
                                                super.paintComponent(g);
                                                Graphics2D g2=(Graphics2D)g;
                                                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                                                g2.setColor(new Color(10,10,20));
                                                g2.fillRect(0,0,getWidth(),getHeight());
                                                int imgW=ffwi*fScale, imgH=ffhi*fScale;
                                                int pw=getWidth(), ph=getHeight();
                                                double sx=(double)pw/imgW, sy=(double)ph/imgH;
                                                double fit=Math.min(sx,sy);
                                                if(fit<1) fit=1;
                                                if(fit>4) fit=4;
                                                int dw=(int)(imgW*fit), dh=(int)(imgH*fit);
                                                int ox=(pw-dw)/2, oy=(ph-dh)/2;
                                                synchronized(fImg){
                                                    g2.drawImage(fImg, ox, oy, dw, dh, null);
                                                }
                                                g2.setColor(new Color(40,40,50));
                                                g2.drawRect(ox-1, oy-1, dw+1, dh+1);
                                                g2.setColor(new Color(0,255,100));
                                                g2.setFont(new Font("Consolas", Font.PLAIN, 11));
                                                g2.drawString("Misha 2D "+ffwi+"x"+ffhi+" | Lite 256M FPS:"+realFps, ox+5, oy+15);
                                                Object lastTxt=fWin.get("__lastText");
                                                if(lastTxt!=null){
                                                    g2.setColor(new Color(0,0,0,180));
                                                    g2.fillRoundRect(ox+3, oy+dh-22, dw-6, 14, 6,6);
                                                    g2.setColor(Color.WHITE);
                                                    g2.setFont(new Font("Consolas", Font.BOLD, 11));
                                                    g2.drawString(String.valueOf(lastTxt), ox+8, oy+dh-12);
                                                }
                                            }
                                        };
                                        panel.setPreferredSize(new Dimension(ffwi*fScale+20, ffhi*fScale+40));
                                        JFrame frame=new JFrame("Misha 2D — "+ffwi+"x"+ffhi+" | Lite 256M | Phenom II");
                                        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                                        frame.add(panel);
                                        frame.pack();
                                        frame.setLocationRelativeTo(null);
                                        frame.setVisible(true);
                                        fWin.put("__frame", frame);
                                        fWin.put("__panel", panel);
                                        frame.addWindowListener(new java.awt.event.WindowAdapter(){
                                            public void windowClosing(java.awt.event.WindowEvent e){
                                                fWin.put("shouldClose", true);
                                            }
                                        });
                                    });
                                } catch(Exception ex){ System.err.println("[MVM] Window invoke failed: "+ex.getMessage()); }
                            }catch(Exception e){ System.err.println("[MVM] Window2D create failed: "+e.getMessage()); }
                        }
                        System.out.println("[2D] Window "+wi+"x"+hi+" created" + (GraphicsEnvironment.isHeadless()?" headless":" window"));
                        stack.push(win);
                        break;
                    }
                    case "clear2D": {
                        Object col = stack.isEmpty()? 0 : stack.pop();
                        Object winO = stack.isEmpty()? null : stack.pop();
                        if(winO instanceof java.util.Map m && m.get("pixels") instanceof int[] pix){
                            int c=(int)toLong(col);
                            int rgb=c;
                            if(c==1) rgb=0xFF3333; else if(c==2) rgb=0x33FF33; else if(c==3) rgb=0x3333FF; else if(c==0) rgb=0x101020;
                            else if(c>0 && c<0xFFFFFF) rgb=c;
                            java.util.Arrays.fill(pix, rgb==0?0:rgb);
                            if(m.get("__image") instanceof BufferedImage img){
                                synchronized(img){
                                    Graphics2D g=img.createGraphics();
                                    g.setColor(new Color(rgb==0?0x101020:rgb));
                                    g.fillRect(0,0,img.getWidth(), img.getHeight());
                                    g.dispose();
                                }
                            }
                        }
                        stack.push(0); break;
                    }
                    case "Rect2D": {
                        Object col = stack.isEmpty()? 0 : stack.pop();
                        Object hh = stack.isEmpty()? 0 : stack.pop();
                        Object ww = stack.isEmpty()? 0 : stack.pop();
                        Object yy = stack.isEmpty()? 0 : stack.pop();
                        Object xx = stack.isEmpty()? 0 : stack.pop();
                        Object winO = stack.isEmpty()? null : stack.pop();
                        if(winO instanceof java.util.Map m && m.get("pixels") instanceof int[] pix){
                            int wi=(int)toLong(m.get("w")), hi=(int)toLong(m.get("h"));
                            int x=(int)toLong(xx), y=(int)toLong(yy), w=(int)toLong(ww), h=(int)toLong(hh), c=(int)toLong(col);
                            int rgb=c;
                            if(c==1) rgb=0xFF5555; else if(c==2) rgb=0x55FF55; else if(c==3) rgb=0x5555FF; else if(c==0) rgb=0x202035;
                            else rgb=c;
                            for(int dy=0; dy<h; dy++) for(int dx=0; dx<w; dx++){
                                int px=x+dx, py=y+dy;
                                if(px>=0 && px<wi && py>=0 && py<hi) pix[py*wi+px]=rgb;
                            }
                            if(m.get("__image") instanceof BufferedImage img){
                                int sc=(int)toLong(m.getOrDefault("__scale",16));
                                synchronized(img){
                                    Graphics2D g=img.createGraphics();
                                    g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                                    g.setColor(new Color(rgb));
                                    g.fillRect(x*sc, y*sc, w*sc, h*sc);
                                    g.setColor(new Color(255,255,255,90));
                                    g.drawRect(x*sc, y*sc, w*sc, h*sc);
                                    g.dispose();
                                }
                            }
                        }
                        stack.push(0); break;
                    }
                    case "Text2D": {
                        Object yy = stack.isEmpty()? 0 : stack.pop();
                        Object xx = stack.isEmpty()? 0 : stack.pop();
                        Object txt = stack.isEmpty()? "" : stack.pop();
                        Object winO = stack.isEmpty()? null : stack.pop();
                        String s=String.valueOf(txt);
                        System.out.println("[2D Text] "+s+" @"+toLong(xx)+","+toLong(yy));
                        if(winO instanceof java.util.Map m){
                            m.put("__lastText", s);
                            m.put("__lastTextX", xx);
                            m.put("__lastTextY", yy);
                        }
                        stack.push(0); break;
                    }
                    case "swapBuffers2D": {
                        Object winO = stack.isEmpty()? null : stack.pop();
                        if(winO instanceof java.util.Map m){
                            int wi=(int)toLong(m.get("w")), hi=(int)toLong(m.get("h"));
                            long now=System.currentTimeMillis();
                            long dt=now - lastSwapMillis;
                            if(dt>0 && dt<1000) realFps=(int)(1000/dt); else if(dt>=1000) realFps= (int)(1000/Math.max(1,dt));
                            lastSwapMillis=now;
                            cubeAngle+=0.18;
                            System.out.println("[2D swap "+wi+"x"+hi+" FPS:"+realFps+"]");
                            if(m.get("__frame") instanceof JFrame f){
                                final String title="Misha 2D — "+wi+"x"+hi+" | Lite 256M | FPS:"+realFps+" | Phenom II";
                                javax.swing.SwingUtilities.invokeLater(() -> f.setTitle(title));
                            }
                            if(m.get("__panel") instanceof JPanel p) javax.swing.SwingUtilities.invokeLater(p::repaint);
                            if(m.get("__frame") instanceof JFrame f) javax.swing.SwingUtilities.invokeLater(() -> { f.repaint(); Toolkit.getDefaultToolkit().sync(); });
                            if(m.get("pixels") instanceof int[] pix && m.get("__image")==null){
                                StringBuilder sb=new StringBuilder();
                                for(int y=0;y<Math.min(10,hi);y++){
                                    for(int x=0;x<Math.min(40,wi);x++) sb.append(pix[y*wi+x]==0?".":"#");
                                    sb.append("\n");
                                }
                                System.out.print(sb.toString());
                            }
                        } else System.out.println("[2D swap]");
                        stack.push(0); break;
                    }
                    case "shouldClose": {
                        Object winO = stack.isEmpty()? null : stack.pop();
                        boolean sc=false;
                        if(winO instanceof java.util.Map m){
                            Object v=m.get("shouldClose");
                            if(v instanceof Boolean b) sc=b;
                            if(m.get("__frame") instanceof JFrame f) sc= sc || !f.isDisplayable();
                        }
                        stack.push(sc);
                        break;
                    }
                    case "MathD3": {
                        Object z = stack.isEmpty()? 0 : stack.pop();
                        Object y = stack.isEmpty()? 0 : stack.pop();
                        Object x = stack.isEmpty()? 0 : stack.pop();
                        java.util.Map<String,Object> v=new java.util.HashMap<>();
                        v.put("__type","MathD3");
                        v.put("x", toDouble(x)); v.put("y", toDouble(y)); v.put("z", toDouble(z));
                        stack.push(v);
                        break;
                    }
                    case "camera": {
                        Object tgt = stack.isEmpty()? null : stack.pop();
                        Object pos = stack.isEmpty()? null : stack.pop();
                        java.util.Map<String,Object> cam=new java.util.HashMap<>();
                        cam.put("__type","Camera3D");
                        cam.put("pos", pos); cam.put("target", tgt);
                        stack.push(cam);
                        break;
                    }
                    case "clear3D": {
                        Object col = stack.isEmpty()? 0 : stack.pop();
                        System.out.println("[3D clear "+col+"]");
                        if(lastWindow2D!=null && lastWindow2D.get("pixels") instanceof int[] pix){
                            int wi=(int)toLong(lastWindow2D.get("w")), hi=(int)toLong(lastWindow2D.get("h"));
                            int c=(int)toLong(col); int rgb=c==0?0x0A0A1A:c;
                            java.util.Arrays.fill(pix, rgb);
                            if(lastWindow2D.get("__image") instanceof BufferedImage img){
                                synchronized(img){
                                    Graphics2D g=img.createGraphics();
                                    g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                                    g.setColor(new Color(rgb));
                                    g.fillRect(0,0,img.getWidth(), img.getHeight());
                                    g.dispose();
                                }
                            }
                        }
                        stack.push(0); break;
                    }
                    case "Cube": {
                        Object col = stack.isEmpty()? 0 : stack.pop();
                        Object sz = stack.isEmpty()? 0 : stack.pop();
                        Object z = stack.isEmpty()? 0 : stack.pop();
                        Object y = stack.isEmpty()? 0 : stack.pop();
                        Object x = stack.isEmpty()? 0 : stack.pop();
                        System.out.println("[3D Cube "+x+","+y+","+z+" s="+sz+" c="+col+"]");
                        if(lastWindow2D!=null && lastWindow2D.get("__image") instanceof BufferedImage img){
                            int imgW=img.getWidth(), imgH=img.getHeight();
                            double cx=toDouble(x), cy=toDouble(y), cz=toDouble(z), hs=toDouble(sz)/2;
                            double ca=Math.cos(cubeAngle), sa=Math.sin(cubeAngle);
                            double ca2=Math.cos(cubeAngle*0.7), sa2=Math.sin(cubeAngle*0.7);
                            double[][] v={
                                {cx-hs, cy-hs, cz-hs},{cx+hs, cy-hs, cz-hs},{cx+hs, cy+hs, cz-hs},{cx-hs, cy+hs, cz-hs},
                                {cx-hs, cy-hs, cz+hs},{cx+hs, cy-hs, cz+hs},{cx+hs, cy+hs, cz+hs},{cx-hs, cy+hs, cz+hs}
                            };
                            int[][] e={{0,1},{1,2},{2,3},{3,0},{4,5},{5,6},{6,7},{7,4},{0,4},{1,5},{2,6},{3,7}};
                            int[] xs=new int[8], ys=new int[8];
                            for(int i=0;i<8;i++){
                                double vx=v[i][0]-cx, vy=v[i][1]-cy, vz=v[i][2]-cz;
                                double rx=vx*ca - vz*sa, rz=vx*sa + vz*ca;
                                double ry=vy*ca2 - rz*sa2, rz2=vy*sa2 + rz*ca2;
                                double nz=rz2 + cz + 8;
                                double scale= 220 / Math.max(1, nz);
                                xs[i]=(int)(imgW/2 + rx*scale);
                                ys[i]=(int)(imgH/2 - ry*scale);
                            }
                            synchronized(img){
                                Graphics2D g=img.createGraphics();
                                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                                g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
                                g.setRenderingHint(java.awt.RenderingHints.KEY_STROKE_CONTROL, java.awt.RenderingHints.VALUE_STROKE_PURE);
                                g.setStroke(new java.awt.BasicStroke(2.4f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
                                int baseCol=(int)toLong(col)==0?0xFF5555:(int)toLong(col);
                                // полупрозрачная заливка граней для HQ
                                java.awt.Polygon front=new java.awt.Polygon();
                                front.addPoint(xs[0],ys[0]); front.addPoint(xs[1],ys[1]); front.addPoint(xs[2],ys[2]); front.addPoint(xs[3],ys[3]);
                                g.setColor(new Color(baseCol & 0xFFFFFF | 0x30000000, true));
                                g.fillPolygon(front);
                                g.setColor(new Color(baseCol));
                                for(int[] ee:e) g.drawLine(xs[ee[0]], ys[ee[0]], xs[ee[1]], ys[ee[1]]);
                                g.setColor(new Color(255,255,255,80));
                                g.setStroke(new java.awt.BasicStroke(1.0f));
                                g.drawLine(xs[0],ys[0],xs[6],ys[6]); g.drawLine(xs[1],ys[1],xs[7],ys[7]);
                                g.dispose();
                            }
                        } else if(lastWindow2D!=null && lastWindow2D.get("pixels") instanceof int[] pix){
                            int wi=(int)toLong(lastWindow2D.get("w")), hi=(int)toLong(lastWindow2D.get("h"));
                            int sx=(int)(wi/2 + toDouble(x)*2), sy=(int)(hi/2 - toDouble(y)*2);
                            int s=(int)(toDouble(sz)*2); if(s<1) s=1;
                            int rgb=(int)toLong(col); if(rgb==0) rgb=0xFF0000;
                            for(int yy=sy; yy<sy+s && yy<hi; yy++) for(int xx=sx; xx<sx+s && xx<wi; xx++) if(xx>=0&&yy>=0) pix[yy*wi+xx]=rgb;
                        }
                        stack.push(0); break;
                    }
                    case "CubeEx": {
                        Object col = stack.isEmpty()? 0 : stack.pop();
                        Object d = stack.isEmpty()? 0 : stack.pop();
                        Object h = stack.isEmpty()? 0 : stack.pop();
                        Object w = stack.isEmpty()? 0 : stack.pop();
                        Object z = stack.isEmpty()? 0 : stack.pop();
                        Object y = stack.isEmpty()? 0 : stack.pop();
                        Object x = stack.isEmpty()? 0 : stack.pop();
                        System.out.println("[3D CubeEx "+x+","+y+","+z+" "+w+"x"+h+"x"+d+" c="+col+"]");
                        if(lastWindow2D!=null && lastWindow2D.get("__image") instanceof BufferedImage img){
                            int imgW=img.getWidth(), imgH=img.getHeight();
                            double cx=toDouble(x), cy=toDouble(y), cz=toDouble(z);
                            double hx=toDouble(w)/2, hy=toDouble(h)/2, hz=toDouble(d)/2;
                            double ca=Math.cos(cubeAngle), sa=Math.sin(cubeAngle);
                            double[][] v={
                                {cx-hx,cy-hy,cz-hz},{cx+hx,cy-hy,cz-hz},{cx+hx,cy+hy,cz-hz},{cx-hx,cy+hy,cz-hz},
                                {cx-hx,cy-hy,cz+hz},{cx+hx,cy-hy,cz+hz},{cx+hx,cy+hy,cz+hz},{cx-hx,cy+hy,cz+hz}
                            };
                            int[][] e={{0,1},{1,2},{2,3},{3,0},{4,5},{5,6},{6,7},{7,4},{0,4},{1,5},{2,6},{3,7}};
                            int[] xs=new int[8], ys=new int[8];
                            for(int i=0;i<8;i++){
                                double vx=v[i][0]-cx, vy=v[i][1]-cy, vz=v[i][2]-cz;
                                double rx=vx*ca - vz*sa, rz=vx*sa + vz*ca;
                                double nz=rz + cz + 6;
                                double s=95/Math.max(0.5,nz);
                                xs[i]=(int)(imgW/2 + rx*s);
                                ys[i]=(int)(imgH/2 - vy*s);
                            }
                            synchronized(img){
                                Graphics2D g=img.createGraphics();
                                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                                g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
                                g.setStroke(new java.awt.BasicStroke(2.2f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
                                int baseCol=(int)toLong(col)==0?0x33FF33:(int)toLong(col);
                                java.awt.Polygon front=new java.awt.Polygon();
                                front.addPoint(xs[0],ys[0]); front.addPoint(xs[1],ys[1]); front.addPoint(xs[2],ys[2]); front.addPoint(xs[3],ys[3]);
                                g.setColor(new Color(baseCol & 0xFFFFFF | 0x28000000, true));
                                g.fillPolygon(front);
                                g.setColor(new Color(baseCol));
                                for(int[] ee:e) g.drawLine(xs[ee[0]],ys[ee[0]],xs[ee[1]],ys[ee[1]]);
                                g.dispose();
                            }
                        }
                        stack.push(0); break;
                    }
                    case "Sphere": {
                        Object col = stack.isEmpty()? 0 : stack.pop();
                        Object r = stack.isEmpty()? 0 : stack.pop();
                        Object z = stack.isEmpty()? 0 : stack.pop();
                        Object y = stack.isEmpty()? 0 : stack.pop();
                        Object x = stack.isEmpty()? 0 : stack.pop();
                        System.out.println("[3D Sphere "+x+","+y+","+z+" r="+r+" c="+col+"]");
                        if(lastWindow2D!=null && lastWindow2D.get("__image") instanceof BufferedImage img){
                            int imgW=img.getWidth(), imgH=img.getHeight();
                            double nz=toDouble(z)+6;
                            double s=95/Math.max(0.5,nz);
                            int sx=(int)(imgW/2 + toDouble(x)*s), sy=(int)(imgH/2 - toDouble(y)*s);
                            int rr=(int)(toDouble(r)*s/2); if(rr<5) rr=5;
                            synchronized(img){
                                Graphics2D g=img.createGraphics();
                                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                                g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
                                int baseCol=(int)toLong(col)==0?0x33AAFF:(int)toLong(col);
                                // заливка с градиентом для HQ
                                java.awt.RadialGradientPaint gp=new java.awt.RadialGradientPaint(sx-rr*0.3f, sy-rr*0.3f, rr*1.2f, new float[]{0f,1f}, new Color[]{new Color(0xFFFFFF), new Color(baseCol)});
                                g.setPaint(gp);
                                g.fillOval(sx-rr, sy-rr, rr*2, rr*2);
                                g.setColor(new Color(255,255,255,120));
                                g.setStroke(new java.awt.BasicStroke(1.8f));
                                g.drawOval(sx-rr, sy-rr, rr*2, rr*2);
                                g.dispose();
                            }
                        }
                        stack.push(0); break;
                    }
                    case "lerp": {
                        Object t = stack.isEmpty()? 0 : stack.pop();
                        Object b = stack.isEmpty()? 0 : stack.pop();
                        Object a = stack.isEmpty()? 0 : stack.pop();
                        double da=toDouble(a), db=toDouble(b), dt=toDouble(t);
                        stack.push(da + (db-da)*dt);
                        break;
                    }
                    case "clamp": {
                        Object mx = stack.isEmpty()? 0 : stack.pop();
                        Object mn = stack.isEmpty()? 0 : stack.pop();
                        Object v = stack.isEmpty()? 0 : stack.pop();
                        double dv=toDouble(v), dmn=toDouble(mn), dmx=toDouble(mx);
                        stack.push(dv < dmn ? dmn : (dv > dmx ? dmx : dv));
                        break;
                    }
                    case "map": {
                        Object outMax = stack.isEmpty()? 0 : stack.pop();
                        Object outMin = stack.isEmpty()? 0 : stack.pop();
                        Object inMax = stack.isEmpty()? 0 : stack.pop();
                        Object inMin = stack.isEmpty()? 0 : stack.pop();
                        Object v = stack.isEmpty()? 0 : stack.pop();
                        double dv=toDouble(v), di1=toDouble(inMin), di2=toDouble(inMax), do1=toDouble(outMin), do2=toDouble(outMax);
                        double res = do1 + (dv - di1) * (do2 - do1) / (di2 - di1 ==0?1:di2-di1);
                        stack.push(res);
                        break;
                    }
                    case "quad": {
                        Object c = stack.isEmpty()? 0 : stack.pop();
                        Object b = stack.isEmpty()? 0 : stack.pop();
                        Object a = stack.isEmpty()? 0 : stack.pop();
                        double da=toDouble(a), db=toDouble(b), dc=toDouble(c);
                        double d = db*db - 4*da*dc;
                        if(d<0) stack.push(0);
                        else stack.push((-db + Math.sqrt(d))/(2*da));
                        break;
                    }
                    case "avg": {
                        // avg(5,3) или avg([1,2,3]) — упрощено: если один List — среднее списка
                        if(stack.isEmpty()){ stack.push(0); break; }
                        Object top = stack.peek();
                        if(top instanceof java.util.List l){
                            stack.pop();
                            double s=0; for(Object o:l) s+=toDouble(o);
                            stack.push(l.isEmpty()?0:s/l.size());
                        } else if(stack.size()>=2){
                            Object b=stack.pop(), a=stack.pop();
                            stack.push((toDouble(a)+toDouble(b))/2);
                        } else {
                            stack.push(toDouble(stack.pop()));
                        }
                        break;
                    }
                    case "inc": {
                        Object a=stack.isEmpty()?0:stack.pop(); stack.push(toDouble(a)+1); break;
                    }
                    case "dec": {
                        Object a=stack.isEmpty()?0:stack.pop(); stack.push(toDouble(a)-1); break;
                    }
                    case "sum": {
                        // sum как функция с 2+ арг: уже инлайнится ADD, но если CALL sum остался — суммируем 2 верхних
                        if(stack.size()>=2){ Object b=stack.pop(), a=stack.pop(); stack.push(toDouble(a)+toDouble(b)); }
                        else if(!stack.isEmpty()) stack.push(stack.pop());
                        else stack.push(0);
                        break;
                    }
                    case "sub": {
                        Object b=stack.isEmpty()?0:stack.pop(); Object a=stack.isEmpty()?0:stack.pop(); stack.push(toDouble(a)-toDouble(b)); break;
                    }
                    case "mult": {
                        Object b=stack.isEmpty()?0:stack.pop(); Object a=stack.isEmpty()?0:stack.pop(); stack.push(toDouble(a)*toDouble(b)); break;
                    }
                    case "div": {
                        Object b=stack.isEmpty()?1:stack.pop(); Object a=stack.isEmpty()?0:stack.pop(); stack.push(toDouble(a)/toDouble(b)); break;
                    }
                    case "mod": {
                        Object b=stack.isEmpty()?1:stack.pop(); Object a=stack.isEmpty()?0:stack.pop(); stack.push(toLong(a)%toLong(b)); break;
                    }
                    default: {
                        if(fn.equals("pow")){
                            Object b = stack.isEmpty()?0:stack.pop();
                            Object a = stack.isEmpty()?0:stack.pop();
                            stack.push(Math.pow(toDouble(a), toDouble(b)));
                        } else if(fn.equals("sqrt")){
                            Object a = stack.isEmpty()?0:stack.pop();
                            stack.push(Math.sqrt(toDouble(a)));
                        } else if(fn.equals("min")){
                            Object b = stack.isEmpty()?0:stack.pop();
                            Object a = stack.isEmpty()?0:stack.pop();
                            stack.push(Math.min(toDouble(a), toDouble(b)));
                        } else if(fn.equals("max")){
                            Object b = stack.isEmpty()?0:stack.pop();
                            Object a = stack.isEmpty()?0:stack.pop();
                            stack.push(Math.max(toDouble(a), toDouble(b)));
                        } else if(fn.equals("abs")){
                            Object a = stack.isEmpty()?0:stack.pop();
                            stack.push(Math.abs(toDouble(a)));
                        } else if(fn.equals("smart_ptr")||fn.equals("cleanup")||fn.equals("realloc")){
                            if(!stack.isEmpty()){ Object a=stack.pop(); if(fn.equals("realloc")&&!stack.isEmpty()) stack.pop(); stack.push(a); } else stack.push(0);
                        } else {
                            System.err.println("[MVM] WARN unknown CALL "+fn+" at ip "+ip+" -> push 0");
                            stack.push(0);
                        }
                        break;
                    }
                }
                break;
            }
            case RET: {
                if(!callStack.isEmpty()){
                    int retIp = callStack.pop();
                    if(!frameStack.isEmpty()){
                        Map<String,Object> prev = frameStack.pop();
                        locals.clear(); locals.putAll(prev);
                    }
                    ip = retIp - 1;
                    break;
                }
                // top-level RET — не halt, просто пропуск (для методов calc и т.п.)
                break;
            }
            case EXIT:
                halted = true;
                break;
            default:
                System.err.println("[MVM] WARN unhandled op "+ins.op+" at ip "+ip);
                break;
        }
    }

    interface Bin { Object apply(Object a, Object b); }
    private void binOp(Bin f){
        Object b = stack.isEmpty()?0:stack.pop();
        Object a = stack.isEmpty()?0:stack.pop();
        stack.push(f.apply(a,b));
    }
    private double toDouble(Object o){
        if(o instanceof Number n) return n.doubleValue();
        if(o instanceof String s) try{return Double.parseDouble(s.trim());}catch(Exception e){return 0;}
        if(o instanceof Boolean b) return b?1:0;
        return 0;
    }
    private long toLong(Object o){
        if(o instanceof Number n) return n.longValue();
        if(o instanceof String s) try{
            String t=s.trim().replaceAll("[lLfFdD]$","");
            if(t.startsWith("0x")||t.startsWith("0X")){
                return Long.parseLong(t.substring(2),16);
            }
            if(t.startsWith("-0x")||t.startsWith("-0X")){
                return -Long.parseLong(t.substring(3),16);
            }
            if(t.contains(".")) return (long)Double.parseDouble(t);
            return Long.parseLong(t);
        }catch(Exception e){return 0;}
        return toBool(o)?1:0;
    }
    private boolean toBool(Object o){
        if(o instanceof Boolean b) return b;
        if(o instanceof Number n) return n.doubleValue()!=0;
        if(o instanceof String s) return !s.isEmpty() && !s.equals("false") && !s.equals("0");
        return o!=null;
    }
    private int compare(Object a,Object b){
        if(a instanceof Number && b instanceof Number) return Double.compare(toDouble(a),toDouble(b));
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    public static void main(String[] args) throws Exception {
        if(args.length==0){ System.out.println("MVM (Misha VM) v1\nUsage: java mvm.MVM program.mvm\n       java mvm.MVM -e \"PUSH \\\"hi\\\"\\nPRINT\""); return; }
        String path = args[0];
        List<Bytecode.Instr> code;
        if(path.equals("-e") && args.length>1){
            code = Bytecode.parseMvmText(args[1]);
        } else {
            String text = java.nio.file.Files.readString(java.nio.file.Path.of(path));
            code = Bytecode.parseMvmText(text);
        }
        System.out.println("// Running on MVM v1 ("+code.size()+" instr)");
        new MVM(code).run();
        System.out.println("\n// halt");
    }
}
