package compiler;

import mvm.Bytecode;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class BootstrapCompiler {
    static final Map<String,String> KW = Map.ofEntries(
        Map.entry("pub","public"), Map.entry("priv","private"), Map.entry("prot","protected"),
        Map.entry("stat","static"), Map.entry("const","final"), Map.entry("abstr","abstract"),
        Map.entry("sync","synchronized"), Map.entry("vol","volatile"), Map.entry("trans","transient"),
        Map.entry("cls","class"), Map.entry("ext","extends"), Map.entry("impl","implements"),
        Map.entry("interf","interface"), Map.entry("noret","void"), Map.entry("ret","return")
    );
    static final Map<String,String> ANNOT = Map.ofEntries(
        Map.entry("#over","@Override"), Map.entry("#dep","@Deprecated"), Map.entry("#supwarn","@SuppressWarnings"), Map.entry("#funcinterf","@FunctionalInterface"),
        Map.entry("#test","@Test"), Map.entry("#before","@Before"), Map.entry("#after","@After"), Map.entry("#beforeclass","@BeforeClass"), Map.entry("#afterclass","@AfterClass"), Map.entry("#ignore","@Ignore"),
        Map.entry("#autowired","@Autowired"), Map.entry("#component","@Component"), Map.entry("#service","@Service"), Map.entry("#repository","@Repository"), Map.entry("#controller","@Controller"), Map.entry("#restcontr","@RestController"),
        Map.entry("#getter","@Getter"), Map.entry("#setter","@Setter"), Map.entry("#tostring","@ToString"), Map.entry("#equalshash","@EqualsAndHashCode"), Map.entry("#noargs","@NoArgsConstructor"), Map.entry("#allargs","@AllArgsConstructor"), Map.entry("#data","@Data"), Map.entry("#builder","@Builder"),
        Map.entry("#secure","@Secure"), Map.entry("#encrypt","@Encrypt"), Map.entry("#validate","@Validate"), Map.entry("#log","@Log"), Map.entry("#auth","@Auth"), Map.entry("#roles","@Roles"), Map.entry("#audit","@Audit"), Map.entry("#sanitize","@Sanitize"),
        Map.entry("#fast","@Fast"), Map.entry("#lazy","@Lazy"), Map.entry("#cache","@Cache"), Map.entry("#async","@Async"), Map.entry("#parallel","@Parallel"), Map.entry("#pool","@Pool"), Map.entry("#memoize","@Memoize"), Map.entry("#prefetch","@Prefetch"),
        Map.entry("#singleton","@Singleton"), Map.entry("#immutable","@Immutable"), Map.entry("#observer","@Observer"), Map.entry("#factory","@Factory"),
        Map.entry("#author","@Author"), Map.entry("#version","@Version"), Map.entry("#since","@Since"), Map.entry("#todo","@Todo"), Map.entry("#fixme","@Fixme"),
        Map.entry("#NoGC","@NoGC"), Map.entry("#ManualMem","@ManualMem"), Map.entry("#StackOnly","@StackOnly"), Map.entry("#HeapOnly","@HeapOnly"), Map.entry("#NoLeak","@NoLeak"), Map.entry("#ZeroOnFree","@ZeroOnFree"), Map.entry("#O1","@O1"), Map.entry("#O2","@O2"), Map.entry("#O3","@O3"), Map.entry("#Os","@Os"), Map.entry("#inline","@Inline")
    );
    static final Map<String,String> ENCRYPT = Map.ofEntries(
        Map.entry("#XOR","@XOR"), Map.entry("#AES","@AES"), Map.entry("#BASE64","@BASE64")
    );
    static boolean hasMathImport = false;
    static boolean hasRandomImport = false;
    static boolean hasFileImport = false;
    static boolean hasMathD2Import = false;
    static boolean hasMathD3Import = false;

    public static void main(String[] args) throws Exception {
        if(args.length==0){
            System.out.println("BootstrapCompiler v1 - Misha (.mih/.mixa)\nUsage:\n  java compiler.BootstrapCompiler source.mih -o out.mvm [-target mvm|java]\n  java compiler.BootstrapCompiler source.mih -o out.java -target java\n  java compiler.BootstrapCompiler --lex source.mih (только токены)");
            return;
        }
        if(args[0].equals("--lex")){
            String src = Files.readString(Path.of(args[1]));
            System.out.println(lexDump(src));
            return;
        }
        String in = args[0];
        String out = "out.mvm";
        String target = "mvm";
        for(int i=1;i<args.length;i++){
            if(args[i].equals("-o") && i+1<args.length) out = args[++i];
            if(args[i].equals("-target") && i+1<args.length) target = args[++i];
        }
        String src = Files.readString(Path.of(in));
        if(target.equals("java")){
            String javaCode = transpileToJava(src);
            Files.writeString(Path.of(out), javaCode);
            System.out.println("OK -> "+out+" (java, "+javaCode.length()+" bytes)");
        } else {
            List<Bytecode.Instr> code = compileToMvm(src);
            String mvmText = Bytecode.disasm(code);
            Files.writeString(Path.of(out), mvmText);
            System.out.println("OK -> "+out+" (mvm, "+code.size()+" instr)");
            System.out.println(mvmText);
        }
    }

    static String lexDump(String src){
        StringBuilder sb = new StringBuilder();
        Pattern p = Pattern.compile("\"[^\"]*\"|'[^']*'|\\b\\w+\\b|==|!=|>=|<=|&&|\\|\\||[{}()\\[\\];,=+\\-*/%<>!]");
        Matcher m = p.matcher(src);
        while(m.find()){
            String tok = m.group();
            String type = "UNKNOWN";
            if(KW.containsKey(tok)) type="KW("+KW.get(tok)+")";
            else if(tok.matches("\".*\"|'.*'")) type="STRING";
            else if(tok.matches("\\d+\\.\\d+[fd]?")) type="FLOAT";
            else if(tok.matches("\\d+[L]?")) type="INT";
            else if(tok.equals("var")||tok.equals("const")||tok.equals("print")||tok.equals("if")||tok.equals("elif")||tok.equals("else")||tok.equals("for")||tok.equals("while")) type="KW";
            else if(tok.matches("[a-zA-Z_][a-zA-Z0-9_]*")) type="IDENT";
            else type="OP";
            sb.append(String.format("%-15s : %s\n", tok, type));
        }
        return sb.toString();
    }

    // Защита строковых литералов от порчи при глобальных заменах
    private static String protectStrings(String src, List<String> store){
        StringBuilder out=new StringBuilder();
        boolean inStr=false; char strCh=0; boolean esc=false;
        StringBuilder cur=null;
        for(int i=0;i<src.length();i++){
            char c=src.charAt(i);
            if(!inStr && (c=='"'||c=='\'')){
                inStr=true; strCh=c; cur=new StringBuilder(); cur.append(c);
            } else if(inStr){
                cur.append(c);
                if(esc){ esc=false; }
                else if(c=='\\') esc=true;
                else if(c==strCh){ inStr=false; String lit=cur.toString(); store.add(lit); out.append("__STR"+(store.size()-1)+"__"); cur=null; }
            } else {
                out.append(c);
            }
        }
        if(cur!=null){ store.add(cur.toString()); out.append("__STR"+(store.size()-1)+"__"); }
        return out.toString();
    }
    private static String restoreStrings(String src, List<String> store){
        for(int i=0;i<store.size();i++) src=src.replace("__STR"+i+"__", store.get(i));
        return src;
    }

    public static String transpileToJava(String src){
        List<String> strStore=new ArrayList<>();
        String tmp=protectStrings(src, strStore);
        boolean hasMath = src.contains("import libs.Math") || src.contains("import libs.*");
        boolean hasRandom = src.contains("import libs.Random") || src.contains("import libs.*");
        boolean hasMathD2 = src.contains("import libs.MathD2") || src.contains("import libs.*");
        boolean hasMathD3 = src.contains("import libs.MathD3") || src.contains("import libs.*");
        boolean hasFile = src.contains("import libs.File") || src.contains("import libs.*");
        String out = tmp;
        for(var e: KW.entrySet()) out = out.replaceAll("\\b"+Pattern.quote(e.getKey())+"\\b", e.getValue());
        List<String> annotKeys = new ArrayList<>(ANNOT.keySet());
        annotKeys.sort((a,b)-> Integer.compare(b.length(), a.length()));
        for(String k: annotKeys) out = out.replace(k, ANNOT.get(k));
        for(var e: ENCRYPT.entrySet()) out = out.replace(e.getKey(), e.getValue());
        out = out.replaceAll("#part\\b","@Part"); out = out.replaceAll("#mosn\\b","@Mosn");
        out = out.replaceAll("#Main\\b","@Main"); out = out.replaceAll("#ModID\\b","@ModID");
        out = out.replaceAll("\\bstr\\b","String"); out = out.replaceAll("\\bflt\\b","float");
        out = out.replaceAll("\\bdbl\\b","double"); out = out.replaceAll("\\bchr\\b","char");
        out = out.replaceAll("\\bbool\\b","boolean"); out = out.replaceAll("\\blng\\b","long");
        out = out.replaceAll("\\blambda\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*\\{", "var $1 = ($2) -> {");
        out = out.replaceAll("(\\w+)\\s*\\?\\?\\s*(\\w+)", "$1 != null ? $1 : $2");
        out = out.replaceAll("(\\w+)\\?\\.(\\w+\\()", "$1 != null ? $1.$2");
        out = out.replaceAll("(\\w+)\\?\\.(\\w+)", "$1 != null ? $1.$2 : null");
        out = out.replaceAll("\\bmatch\\s+(\\w+)\\s*\\{", "switch($1) {");
        out = out.replaceAll("^\\s*_\\s*->", "default:");
        out = out.replaceAll("([\\w\"\\d]+)\\s*->", "case $1:");
        out = out.replaceAll("\\belif\\b","else if");
        out = out.replaceAll("if\\s+(.+?)\\s*->\\s*(print\\(.+?\\))","if ($1) $2;");
        out = out.replaceAll("\\bprint\\s*\\(","System.out.println(");
        out = out.replaceAll("\\.lower\\(\\)",".toLowerCase()"); out = out.replaceAll("\\.upper\\(\\)",".toUpperCase()");
        out = out.replaceAll("\\.trim\\(\\)",".trim()"); out = out.replaceAll("\\.capitalize\\(\\)",".substring(0,1).toUpperCase()+substring(1)");
        out = out.replaceAll("\\.contains\\(",".contains("); out = out.replaceAll("\\.startsWith\\(",".startsWith(");
        out = out.replaceAll("\\.endsWith\\(",".endsWith("); out = out.replaceAll("\\.isEmpty\\(\\)",".isEmpty()");
        out = out.replaceAll("\\.reverse\\(\\)",".reverse().toString()");
        out = out.replaceAll("\\.charAt\\(",".charAt("); out = out.replaceAll("\\.index\\(",".indexOf(");
        out = out.replaceAll("var\\s+(\\w+)\\s*=\\s*\\[([^\\]]*)\\]", "var $1 = new Object[]{$2}");
        out = out.replaceAll("\\bList\\(\\)","new java.util.ArrayList<>()"); out = out.replaceAll("\\bMap\\(\\)","new java.util.HashMap<>()"); out = out.replaceAll("\\bSet\\(\\)","new java.util.HashSet<>()");
        String[] mathBuiltins = {"sum","sub","mult","div","mod","pow","sqrt","abs","min","max","avg","inc","dec",
            "strlen","concat","substr","replace","contains","index","split","upper","lower","trim","repeat",
            "len","first","last","append","prepend","remove","reverse","sort","unique","isEmpty",
            "and","or","not","xor","round","floor","ceil","isInt","isString","isArray","isNull","typeOf",
            "toInt","toString","toFloat","toArray","sleep","exit","now","date","time"};
        String[] randomBuiltins = {"randint","randfloat","choice","choices","shuffle","randbool","randchar","randstring","randrange"};
        if(hasMath){
            for(String b: mathBuiltins) out = out.replaceAll("(?<!\\.)\\b"+Pattern.quote(b)+"\\s*\\(","misha.Runtime."+b+"(");
        }
        if(hasRandom){
            for(String b: randomBuiltins) out = out.replaceAll("(?<!\\.)\\b"+Pattern.quote(b)+"\\s*\\(","misha.Runtime."+b+"(");
        }
        if(hasFile){
            for(String b: new String[]{"read","write","exists","appendFile","delete","listFiles","mkdir","fetch","httpGet","httpPost"}){
                out = out.replaceAll("(?<!\\.)\\b"+Pattern.quote(b)+"\\s*\\(","misha.Runtime."+b+"(");
            }
        }
        out = out.replaceAll("System\\.out\\.misha\\.Runtime\\.println","System.out.println");
        out = out.replaceAll("misha\\.Runtime\\.misha\\.Runtime\\.","misha.Runtime.");
        out = out.replaceAll("catch\\s+(\\w+)\\s*\\{","catch (Exception $1) {");
        out = out.replaceAll("\\balloc\\s*\\(","misha.Runtime.alloc("); out = out.replaceAll("\\bfree\\s*\\(","misha.Runtime.free(");
        out = out.replaceAll("\\bgc\\s*\\(","misha.Runtime.gc(");
        out = out.replaceAll("#macro.*","//$0"); out = out.replaceAll("#IF.*","//$0"); out = out.replaceAll("#ELSE.*","//$0"); out = out.replaceAll("#END.*","//$0");
        out = out.replaceAll("#generate.*","//$0"); out = out.replaceAll("#operator","@Operator");
        out = out.replaceAll(":\\s*super\\s*\\(([^)]*)\\)\\s*\\{","{ super($1);");
        // интерполяция только внутри System.out.println
        Pattern interp = Pattern.compile("System\\.out\\.println\\(\"([^\"]*__STR\\d+__[^\"]*\\{[^}]+\\}[^\"]*)\"\\)");
        // проще: исходная логика с __STR защитой уже не нужна — делаем на восстановленном
        out = restoreStrings(out, strStore);
        Pattern interp2 = Pattern.compile("System\\.out\\.println\\(\"([^\"]*\\{[^}]+\\}[^\"]*)\"\\)");
        Matcher mim = interp2.matcher(out);
        StringBuffer sb = new StringBuffer();
        while(mim.find()){
            String inner = mim.group(1);
            String repl = inner.replaceAll("\\{([^}]+)\\}", "\" + ($1) + \"");
            repl = repl.replaceAll("\"\\s*\\+\\s*\"","");
            mim.appendReplacement(sb, Matcher.quoteReplacement("System.out.println(\""+repl+"\")"));
        }
        mim.appendTail(sb);
        out = sb.length()>0 ? sb.toString() : out;
        return "// Generated by BootstrapCompiler v2 (Misha .mih/.mixa -> Java) - покрывает 20 разделов\n" +
               "// Source: Misha spec 1-20\n" + out;
    }

    // ===== MVM helpers =====
    private static String stripQuotes(String s){
        s=s.trim();
        if((s.startsWith("\"")&&s.endsWith("\"")&&s.length()>=2)||(s.startsWith("'")&&s.endsWith("'")&&s.length()>=2)){
            return s.substring(1,s.length()-1);
        }
        return s;
    }
    private static List<String> splitArgsRespectingStringsAndBrackets(String args){
        List<String> res=new ArrayList<>();
        if(args.trim().isEmpty()) return res;
        StringBuilder cur=new StringBuilder();
        boolean inStr=false; char strCh=0; boolean esc=false;
        int depthPar=0, depthBr=0;
        for(int i=0;i<args.length();i++){
            char c=args.charAt(i);
            if(esc){ cur.append(c); esc=false; continue; }
            if(c=='\\'){ esc=true; cur.append(c); continue; }
            if(!inStr && (c=='"'||c=='\'')){ inStr=true; strCh=c; cur.append(c); }
            else if(inStr && c==strCh){ inStr=false; cur.append(c); }
            else if(!inStr){
                if(c=='(') depthPar++;
                else if(c==')') depthPar--;
                else if(c=='[') depthBr++;
                else if(c==']') depthBr--;
                else if(c==',' && depthPar==0 && depthBr==0){ res.add(cur.toString().trim()); cur.setLength(0); continue; }
                cur.append(c);
            } else cur.append(c);
        }
        if(cur.length()>0) res.add(cur.toString().trim());
        return res;
    }
    private static List<Object> parseArrayElements(String inner){
        List<Object> out=new ArrayList<>();
        if(inner.trim().isEmpty()) return out;
        List<String> toks=splitArgsRespectingStringsAndBrackets(inner);
        for(String e: toks){
            e=e.trim();
            if(e.isEmpty()) continue;
            if(e.startsWith("[") && e.endsWith("]")){
                String sub=e.substring(1,e.length()-1);
                out.add(parseArrayElements(sub));
            } else if((e.startsWith("\"")&&e.endsWith("\""))||(e.startsWith("'")&&e.endsWith("'"))){
                out.add(stripQuotes(e));
            } else if(e.matches("-?\\d+")) {
                try{ out.add(Integer.parseInt(e)); }catch(Exception ex){ out.add(Long.parseLong(e)); }
            } else if(e.matches("-?\\d+\\.\\d+([fFdD])?")||e.matches("-?\\d+[fFdDlL]")){
                String t=e.replaceAll("[fFdDlL]$","");
                try{ out.add(Double.parseDouble(t)); }catch(Exception ex){ out.add(e); }
            } else if(e.equals("true")||e.equals("false")) out.add(Boolean.parseBoolean(e));
            else if(e.equals("null")) out.add(null);
            else {
                // переменная — оставим как строку-имя, но len сработает только если это List/String
                // для константных массивов с переменными — кладем как строку, MVM len вернет 0; лучше попытаться оставить как есть
                out.add(e);
            }
        }
        return out;
    }

    private static String extractCond(String line){
        // line like "if age > 18 {" или "if (age > 18) {"
        String t=line.trim();
        if(t.startsWith("if")) t=t.substring(2).trim();
        if(t.startsWith("(")){
            int depth=0; int end=-1;
            for(int i=0;i<t.length();i++){
                char c=t.charAt(i);
                if(c=='(') depth++;
                else if(c==')'){ depth--; if(depth==0){ end=i; break; } }
            }
            if(end!=-1){
                return t.substring(1,end).trim();
            }
        }
        int brace=t.indexOf("{");
        if(brace!=-1) t=t.substring(0,brace).trim();
        if(t.endsWith(")")){ /* may have stray ) */ }
        t=t.replaceAll("^\\(|\\)$","").trim();
        // убрать trailing {
        t=t.replaceAll("\\{\\s*$","").trim();
        return t;
    }
    private static String afterBrace(String line){
        int p=line.indexOf("{");
        if(p==-1) return "";
        return line.substring(p+1).trim();
    }
    private static int findMatchingParen(String s, int openPos){
        int d=0; boolean inStr=false; char ch=0; boolean esc=false;
        for(int i=openPos;i<s.length();i++){
            char c=s.charAt(i);
            if(esc){ esc=false; continue; }
            if(c=='\\'){ esc=true; continue; }
            if(!inStr && (c=='"'||c=='\'')){ inStr=true; ch=c; }
            else if(inStr && c==ch){ inStr=false; }
            else if(!inStr){
                if(c=='(') d++;
                else if(c==')'){ d--; if(d==0) return i; }
            }
        }
        return -1;
    }

    private static List<String> expandSource(String src){
        List<String> tmp=new ArrayList<>();
        // Phase 1: разбить каждую строку по { и } вне строк
        for(String raw: src.split("\n")){
            String t=raw.trim();
            if(t.isEmpty()||t.startsWith("//")||t.startsWith("#")||t.startsWith("package")||t.startsWith("import")||t.matches("(pub\\s+)?(const\\s+)?(abstr\\s+)?class.*")||t.matches("interf.*")){
                tmp.add(raw);
                continue;
            }
            // Не разбивать match и map literal (lambda разбиваем)
            if(t.startsWith("match") ){
                tmp.add(raw);
                continue;
            }
            // Map literal: var m = {"a": 1} — не разбивать фигурные скобки
            if(t.contains("=") && t.contains("{") && t.contains(":") && t.contains("\"") && t.indexOf("=") < t.indexOf("{")){
                tmp.add(raw);
                continue;
            }
            // Box<String>() и т.п. — тоже не разбивать < > ?
            StringBuilder cur=new StringBuilder();
            boolean inStr=false; char strCh=0; boolean esc=false;
            for(int i=0;i<raw.length();i++){
                char c=raw.charAt(i);
                if(esc){ cur.append(c); esc=false; continue; }
                if(c=='\\'){ esc=true; cur.append(c); continue; }
                if(!inStr && (c=='"'||c=='\'')){ inStr=true; strCh=c; cur.append(c); }
                else if(inStr && c==strCh){ inStr=false; cur.append(c); }
                else if(!inStr && (c=='{'||c=='}')){
                    String before=cur.toString().trim();
                    if(!before.isEmpty()) tmp.add(before);
                    tmp.add(String.valueOf(c));
                    cur.setLength(0);
                    // пропустить пробелы после скобки
                    while(i+1<raw.length() && Character.isWhitespace(raw.charAt(i+1))) i++;
                } else cur.append(c);
            }
            String tail=cur.toString().trim();
            if(!tail.isEmpty()) tmp.add(tail);
        }
        // Phase 2: склеить управляющие конструкции с {
        List<String> out=new ArrayList<>();
        for(int i=0;i<tmp.size();i++){
            String cur=tmp.get(i).trim();
            if(cur.isEmpty()) continue;
            // склеить if/for/while/match/lambda/else с следующей {
            if((cur.startsWith("if ")||cur.startsWith("if(")||cur.startsWith("for")||cur.startsWith("while")||cur.startsWith("match")||cur.startsWith("lambda")||cur.equals("else")||cur.startsWith("else ")||cur.startsWith("elif")) && i+1<tmp.size() && tmp.get(i+1).trim().equals("{")){
                out.add(cur+" {");
                i++;
            } else if(cur.equals("}") && i+1<tmp.size() && (tmp.get(i+1).trim().startsWith("else")||tmp.get(i+1).trim().startsWith("elif"))){
                // } + else  -> } else {  (если else уже с {, будет склеено выше)
                String nxt=tmp.get(i+1).trim();
                if(nxt.equals("else") && i+2<tmp.size() && tmp.get(i+2).trim().equals("{")){
                    out.add("} else {");
                    i+=2;
                } else if(nxt.startsWith("else ") && nxt.endsWith("{")){
                    out.add("} "+nxt);
                    i++;
                } else if(nxt.startsWith("elif")){
                    // } + elif ...  -> } elif ... {
                    if(nxt.endsWith("{")){
                        out.add("} "+nxt);
                        i++;
                    } else if(i+2<tmp.size() && tmp.get(i+2).trim().equals("{")){
                        out.add("} "+nxt+" {");
                        i+=2;
                    } else {
                        out.add("}");
                        out.add(nxt);
                    }
                } else {
                    out.add("}");
                    // else будет на следующей итерации
                }
            } else {
                out.add(cur);
            }
        }
        // Phase 3: разбить тела, где несколько стейтментов в одной строке без ;  (например print(...) w=...)
        List<String> finalOut=new ArrayList<>();
        for(String line: out){
            String tr=line.trim();
            if(tr.equals("{")||tr.equals("}")||tr.startsWith("if ")||tr.startsWith("if(")||tr.startsWith("for")||tr.startsWith("while")||tr.startsWith("match")||tr.startsWith("} else")||tr.startsWith("} elif")||tr.startsWith("else")||tr.startsWith("elif")||tr.startsWith("lambda")||tr.startsWith("package")||tr.startsWith("import")||tr.startsWith("//")||tr.startsWith("#")){
                finalOut.add(line);
            } else if(tr.contains(";")){
                for(String p: tr.split(";")){
                    p=p.trim();
                    if(!p.isEmpty()) finalOut.add(p);
                }
            } else if(tr.startsWith("print") && tr.contains("print")==false){
                finalOut.add(line);
            } else {
                // проверить несколько стейтментов без ;  например 'print("a") w = w - 1'
                // Если строка начинается с print и после ) есть остаток
                if(tr.startsWith("print")){
                    int open=tr.indexOf("(");
                    int close=findMatchingParen(tr, open);
                    if(close!=-1 && close+1 < tr.length()){
                        String rest=tr.substring(close+1).trim();
                        if(!rest.isEmpty()){
                            finalOut.add(tr.substring(0,close+1).trim());
                            // остаток может содержать еще несколько стейтментов
                            List<String> restParts=splitBodyStatements(rest);
                            finalOut.addAll(restParts);
                            continue;
                        }
                    }
                }
                // var x=... y=... ?
                // Попробуем разбить по границе ') ' + слово
                // Для простоты: если внутри есть 'print(' второй раз, разбить
                int secondPrint=tr.indexOf("print", 1);
                if(secondPrint!=-1){
                    // найдем где заканчивается первый стейтмент
                    // Эвристика: взять до второго print как первый
                    // Но лучше через findMatchingParen
                }
                finalOut.add(line);
            }
        }
        return finalOut;
    }
    private static List<String> splitBodyStatements(String body){
        List<String> res=new ArrayList<>();
        // Сначла по ;
        String[] parts=body.split(";");
        for(String p: parts){
            p=p.trim();
            if(p.isEmpty()) continue;
            // если внутри p несколько стейтментов без ; — например print(...) w = ...
            // Найдем границу после ) вне строк
            List<String> sub=splitMultipleStatements(p);
            res.addAll(sub);
        }
        return res;
    }
    private static List<String> splitMultipleStatements(String line){
        List<String> out=new ArrayList<>();
        // Простая эвристика: найти print(...) затем остаток
        String t=line.trim();
        if(t.startsWith("print")){
            int open=t.indexOf("(");
            if(open!=-1){
                int close=findMatchingParen(t, open);
                if(close!=-1 && close+1 < t.length()){
                    String first=t.substring(0,close+1).trim();
                    String rest=t.substring(close+1).trim();
                    if(!first.isEmpty()) out.add(first);
                    if(!rest.isEmpty()){
                        // rest может быть еще несколько стейтментов
                        out.addAll(splitMultipleStatements(rest));
                        return out;
                    } else {
                        return out;
                    }
                }
            }
        }
        // var ... print ... ?
        // Попробуем найти ; уже сделали, иначе один стейтмент
        out.add(t);
        return out;
    }

    public static List<Bytecode.Instr> compileToMvm(String src){
        hasMathImport = src.contains("import libs.Math") || src.contains("import libs.*");
        hasRandomImport = src.contains("import libs.Random") || src.contains("import libs.*");
        hasFileImport = src.contains("import libs.File") || src.contains("import libs.*");
        hasMathD2Import = src.contains("import libs.MathD2") || src.contains("import libs.*");
        hasMathD3Import = src.contains("import libs.MathD3") || src.contains("import libs.*");
        List<Bytecode.Instr> code = new ArrayList<>();
        Deque<String[]> loopStack = new ArrayDeque<>();
        Deque<String[]> lambdaStack = new ArrayDeque<>();
        Deque<String> matchStack = new ArrayDeque<>();
        Deque<String[]> ifStack = new ArrayDeque<>();
        Deque<String[]> doStack = new ArrayDeque<>();
        Deque<String> blockStack = new ArrayDeque<>();
        Map<String, List<String>> lambdaParams = new HashMap<>();
        List<String> lines = expandSource(src);
        int labelId=0;
        // очередь для обработки виртуальных строк (расширенных)
        Deque<String> q=new ArrayDeque<>(lines);
        while(!q.isEmpty()){
            String raw=q.pollFirst();
            String line = raw.trim();
            if(line.isEmpty()||line.startsWith("//")||line.startsWith("package")||line.startsWith("import")) {
                // пропускаем аннотации #... но не #part/#mosn которые уже в expand?
                if(line.startsWith("#")) continue;
                continue;
            }
            if(line.startsWith("enum ")){
                blockStack.push("ENUM");
                continue;
            }
            if(!blockStack.isEmpty() && blockStack.peek().equals("ENUM")){
                if(line.equals("}")){
                    blockStack.pop();
                }
                continue;
            }
            if(line.matches("(pub\\s+)?(const\\s+)?(abstr\\s+)?class.*") || line.matches("interf.*") || line.matches("pub\\s+cls\\s+.*\\{") || line.equals("{")) {
                // для простоты класс/интерфейс игнорируем заголовки, но "{" уже отдельно
                if(line.equals("{")||line.equals("}")) {
                    // обработка закрытия ниже (лямбды/циклы/if)
                } else continue;
            }
            // пропускаем содержимое pub cls ... { ... } — но run() и т.п. уже handled
            // Если это заголовок класса без runAll — пропускаем тело до }
            if(line.matches("pub\\s+cls\\s+\\w+.*") && !line.contains("run") && !line.contains("main")){
                // класс — уже пропущен выше, но если вдруг
            }
            if(line.startsWith("#")) continue;

            // lambda
            if(line.startsWith("lambda")){
                Matcher lm = Pattern.compile("lambda\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*\\{?").matcher(line);
                if(lm.find()){
                    String name = lm.group(1);
                    String params = lm.group(2).trim();
                    List<String> plist = new ArrayList<>();
                    if(!params.isEmpty()){
                        for(String p: params.split(",")){
                            String[] toks = p.trim().split("\\s+");
                            String pn = toks[toks.length-1].trim();
                            if(!pn.isEmpty()) plist.add(pn);
                        }
                    }
                    lambdaParams.put(name, plist);
                    String lblLambda = "Llambda_"+name;
                    String lblEnd = "Lend_lambda_"+name+"_"+(labelId++);
                    code.add(new Bytecode.Instr(Bytecode.Op.JMP, lblEnd));
                    code.add(new Bytecode.Instr(Bytecode.Op.LABEL, lblLambda));
                    for(int i=plist.size()-1; i>=0; i--){
                        String pn = plist.get(i);
                        if(!pn.isEmpty()) code.add(new Bytecode.Instr(Bytecode.Op.STORE, pn));
                    }
                    lambdaStack.push(new String[]{name, lblLambda, lblEnd});
                    blockStack.push("LAMBDA");
                    continue;
                }
                continue;
            }

            // методы pub int calc(int a, int b) {  -> функция или инлайн
            Matcher methM = Pattern.compile("(?:pub|priv|prot|stat|const|abstr|sync|vol|trans|final)\\s+(?:\\w+\\s+)?(\\w+)\\s*\\(([^)]*)\\)\\s*\\{").matcher(line);
            if(methM.find()){
                String mName = methM.group(1);
                String mParams = methM.group(2).trim();
                // runAll/main/hello/testAll — инлайн (пропускаем только заголовок)
                if(mName.equals("runAll") || mName.equals("main") || mName.equals("hello") || mName.equals("testAll") || mName.equals("run") || mName.equals("test")){
                    continue;
                } else {
                    // остальные методы — как функция (JMP over)
                    String lblFunc = "Lfunc_"+mName;
                    String lblEnd = "Lend_func_"+mName+"_"+(labelId++);
                    code.add(new Bytecode.Instr(Bytecode.Op.JMP, lblEnd));
                    code.add(new Bytecode.Instr(Bytecode.Op.LABEL, lblFunc));
                    if(!mParams.isEmpty()){
                        String[] ps=mParams.split(",");
                        for(int i=ps.length-1;i>=0;i--){
                            String tok=ps[i].trim();
                            if(tok.isEmpty()) continue;
                            String[] toks=tok.trim().split("\\s+");
                            String pn=toks[toks.length-1].trim();
                            if(!pn.isEmpty()) code.add(new Bytecode.Instr(Bytecode.Op.STORE, pn));
                        }
                    }
                    lambdaStack.push(new String[]{mName, lblFunc, lblEnd});
                    blockStack.push("LAMBDA");
                    continue;
                }
            }

            // match
            if(line.startsWith("match")){
                Matcher mm = Pattern.compile("match\\s+(\\w+)\\s*\\{").matcher(line);
                if(mm.find()){
                    String var = mm.group(1);
                    compileExpr(var, code);
                    String lblEnd = "LmatchEnd"+(labelId++);
                    matchStack.push(lblEnd);
                    blockStack.push("MATCH");
                    continue;
                }
                // без { на той же строке — expand уже разнес
                Matcher mm2 = Pattern.compile("match\\s+(\\w+)").matcher(line);
                if(mm2.find()){
                    String var = mm2.group(1);
                    compileExpr(var, code);
                    String lblEnd = "LmatchEnd"+(labelId++);
                    matchStack.push(lblEnd);
                    blockStack.push("MATCH");
                    continue;
                }
            }
            if(!matchStack.isEmpty() && line.contains("->")){
                String lblEnd = matchStack.peek();
                String t = line.trim();
                if(t.startsWith("_") && t.contains("->")){
                    String body = t.replaceFirst(".*_\\s*->\\s*","").trim();
                    if(body.startsWith("{")) body=body.substring(1).trim();
                    if(body.endsWith("}")) body=body.substring(0, body.length()-1).trim();
                    code.add(new Bytecode.Instr(Bytecode.Op.POP));
                    if(!body.isEmpty()){
                        if(body.startsWith("print")){
                            Matcher pm = Pattern.compile("print\\s*\\(\\s*\"([^\"]*)\"\\s*\\)").matcher(body);
                            if(pm.find()){ code.add(new Bytecode.Instr(Bytecode.Op.PUSH, pm.group(1))); code.add(new Bytecode.Instr(Bytecode.Op.PRINTLN)); }
                            else { Matcher pm2 = Pattern.compile("print\\s*\\(\\s*(.+)\\s*\\)").matcher(body); if(pm2.find()){ compileExpr(pm2.group(1), code); code.add(new Bytecode.Instr(Bytecode.Op.PRINTLN)); } }
                        } else compileExpr(body, code);
                    }
                    code.add(new Bytecode.Instr(Bytecode.Op.JMP, lblEnd));
                    continue;
                } else if(t.contains("->")){
                    String[] parts = t.split("->",2);
                    String val = parts[0].trim();
                    String body = parts[1].trim();
                    if(body.startsWith("{")) body=body.substring(1).trim();
                    if(body.endsWith("}")) body=body.substring(0, body.length()-1).trim();
                    String lblNext = "LmatchNext"+(labelId++);
                    code.add(new Bytecode.Instr(Bytecode.Op.DUP));
                    compileExpr(val, code);
                    code.add(new Bytecode.Instr(Bytecode.Op.EQ));
                    code.add(new Bytecode.Instr(Bytecode.Op.CJMP, lblNext));
                    code.add(new Bytecode.Instr(Bytecode.Op.POP));
                    if(!body.isEmpty()){
                        if(body.startsWith("print")){
                            Matcher pm = Pattern.compile("print\\s*\\(\\s*\"([^\"]*)\"\\s*\\)").matcher(body);
                            if(pm.find()){ code.add(new Bytecode.Instr(Bytecode.Op.PUSH, pm.group(1))); code.add(new Bytecode.Instr(Bytecode.Op.PRINTLN)); }
                            else { Matcher pm2 = Pattern.compile("print\\s*\\(\\s*(.+)\\s*\\)").matcher(body); if(pm2.find()){ compileExpr(pm2.group(1), code); code.add(new Bytecode.Instr(Bytecode.Op.PRINTLN)); } }
                        } else compileExpr(body, code);
                    }
                    code.add(new Bytecode.Instr(Bytecode.Op.JMP, lblEnd));
                    code.add(new Bytecode.Instr(Bytecode.Op.LABEL, lblNext));
                    continue;
                }
            }
            if(line.equals("}") && !matchStack.isEmpty()){
                String lblEnd = matchStack.pop();
                code.add(new Bytecode.Instr(Bytecode.Op.POP));
                code.add(new Bytecode.Instr(Bytecode.Op.LABEL, lblEnd));
                continue;
            }

            // for/while/do — до var
            if(line.startsWith("for")){
                Matcher fm = Pattern.compile("for\\s*\\(\\s*var\\s+(\\w+)\\s*=\\s*([^;]+);\\s*([^;]+);\\s*([^\\)]+)\\)\\s*\\{?").matcher(line);
                if(fm.find()){
                    String v=fm.group(1); String init=fm.group(2).trim(); String cond=fm.group(3).trim(); String upd=fm.group(4).trim();
                    compileExpr(init, code); code.add(new Bytecode.Instr(Bytecode.Op.STORE, v));
                    String lblLoop="Lloop"+labelId; String lblEnd="Lend"+labelId; labelId++;
                    code.add(new Bytecode.Instr(Bytecode.Op.LABEL, lblLoop));
                    compileExpr(cond, code); code.add(new Bytecode.Instr(Bytecode.Op.CJMP, lblEnd));
                    loopStack.push(new String[]{"FOR", v, upd, lblLoop, lblEnd});
                    blockStack.push("FOR");
                    continue;
                }
                Matcher fm2 = Pattern.compile("for\\s+(\\w+)\\s*:\\s*(.+)").matcher(line);
                if(fm2.find()){
                    String var=fm2.group(1).trim();
                    String arrExpr=fm2.group(2).trim().replaceAll("\\{\\s*$","").trim();
                    // foreach: var item : arr
                    String arrVar="__arr"+labelId;
                    String idxVar="__idx"+labelId;
                    String lblLoop="Lloop"+labelId; String lblEnd="Lend"+labelId; labelId++;
                    // arrVar = arrExpr
                    compileExpr(arrExpr, code); code.add(new Bytecode.Instr(Bytecode.Op.STORE, arrVar));
                    code.add(new Bytecode.Instr(Bytecode.Op.PUSH, 0)); code.add(new Bytecode.Instr(Bytecode.Op.STORE, idxVar));
                    code.add(new Bytecode.Instr(Bytecode.Op.LABEL, lblLoop));
                    code.add(new Bytecode.Instr(Bytecode.Op.LOAD, idxVar));
                    code.add(new Bytecode.Instr(Bytecode.Op.LOAD, arrVar));
                    code.add(new Bytecode.Instr(Bytecode.Op.CALL, "len"));
                    code.add(new Bytecode.Instr(Bytecode.Op.LT));
                    code.add(new Bytecode.Instr(Bytecode.Op.CJMP, lblEnd));
                    // var = arr[idx]
                    code.add(new Bytecode.Instr(Bytecode.Op.LOAD, arrVar));
                    code.add(new Bytecode.Instr(Bytecode.Op.LOAD, idxVar));
                    code.add(new Bytecode.Instr(Bytecode.Op.CALL, "getAt"));
                    code.add(new Bytecode.Instr(Bytecode.Op.STORE, var));
                    loopStack.push(new String[]{"FOREACH", var, arrVar, idxVar, lblLoop, lblEnd});
                    blockStack.push("FOREACH");
                    continue;
                }
                // for без var?
            }
            if(line.startsWith("while")){
                String t = line.trim().substring(5).trim();
                String cond;
                if(t.startsWith("(")){
                    int depth=0; int end=-1;
                    for(int i=0;i<t.length();i++){
                        char c=t.charAt(i);
                        if(c=='(') depth++;
                        else if(c==')'){ depth--; if(depth==0){ end=i; break; } }
                    }
                    if(end!=-1) cond = t.substring(1,end).trim();
                    else {
                        int brace=t.indexOf("{");
                        if(brace!=-1) t=t.substring(0,brace).trim();
                        cond = t.replaceAll("^\\(|\\)$","").trim();
                    }
                } else {
                    int brace=t.indexOf("{");
                    if(brace!=-1) t=t.substring(0,brace).trim();
                    cond = t.replaceAll("^\\(|\\)$","").trim();
                }
                cond = cond.replaceAll("\\{\\s*$","").trim();
                String lblLoop="Lwhile"+labelId; String lblEnd="Lwend"+labelId; labelId++;
                code.add(new Bytecode.Instr(Bytecode.Op.LABEL, lblLoop));
                compileExpr(cond, code); code.add(new Bytecode.Instr(Bytecode.Op.CJMP, lblEnd));
                loopStack.push(new String[]{"WHILE", lblLoop, lblEnd});
                blockStack.push("WHILE");
                continue;
            }
            if(line.startsWith("do")){
                String lblDo="Ldo"+labelId; String lblEnd="LdoEnd"+labelId; labelId++;
                code.add(new Bytecode.Instr(Bytecode.Op.LABEL, lblDo));
                doStack.push(new String[]{lblDo, lblEnd});
                blockStack.push("DO");
                continue;
            }
            if(line.matches("\\}\\s*while.*") && !doStack.isEmpty()){
                String t2 = line.replaceFirst("\\}\\s*while\\s*","").trim();
                String cond;
                if(t2.startsWith("(")){
                    int depth=0; int end=-1;
                    for(int i=0;i<t2.length();i++){
                        char c=t2.charAt(i);
                        if(c=='(') depth++;
                        else if(c==')'){ depth--; if(depth==0){ end=i; break; } }
                    }
                    if(end!=-1) cond = t2.substring(1,end).trim();
                    else {
                        int brace=t2.indexOf("{");
                        if(brace!=-1) t2=t2.substring(0,brace).trim();
                        cond = t2.replaceAll("^\\(|\\)$","").trim();
                    }
                } else {
                    int brace=t2.indexOf("{");
                    if(brace!=-1) t2=t2.substring(0,brace).trim();
                    cond = t2.replaceAll("^\\(|\\)$","").trim();
                }
                cond = cond.replaceAll("\\{\\s*$","").trim();
                String[] ctx=doStack.pop();
                compileExpr(cond, code);
                code.add(new Bytecode.Instr(Bytecode.Op.CJMP, ctx[1])); // if false jump to end? точнее if true loop
                // do-while: if cond true loop
                // CJMP прыгает если false, поэтому CJMP end если false, иначе JMP do
                // Мы сделали CJMP end, нужен еще JMP do если true
                // Реализация: CJMP end, JMP do, LABEL end
                code.add(new Bytecode.Instr(Bytecode.Op.JMP, ctx[0]));
                code.add(new Bytecode.Instr(Bytecode.Op.LABEL, ctx[1]));
                continue;
            }
            if(line.equals("{")){
                continue;
            }
            // обработка }
            if(line.equals("}")){
                if(blockStack.isEmpty()){
                    continue;
                }
                String topType = blockStack.peek();
                if(topType.equals("LAMBDA")){
                    blockStack.pop();
                    String[] info = lambdaStack.pop();
                    String name=info[0]; String lblLambda=info[1]; String lblEnd=info[2];
                    boolean hasRet = false;
                    for(int k=code.size()-1; k>=0 && k>code.size()-6; k--){
                        if(k<0) break;
                        if(code.get(k).op==Bytecode.Op.RET){ hasRet=true; break; }
                        if(code.get(k).op==Bytecode.Op.LABEL) break;
                    }
                    if(!hasRet) code.add(new Bytecode.Instr(Bytecode.Op.RET));
                    code.add(new Bytecode.Instr(Bytecode.Op.LABEL, lblEnd));
                    code.add(new Bytecode.Instr(Bytecode.Op.PUSH, lblLambda));
                    code.add(new Bytecode.Instr(Bytecode.Op.STORE, name));
                    continue;
                } else if(topType.equals("FOR")||topType.equals("FOREACH")||topType.equals("WHILE")){
                    blockStack.pop();
                    String[] top = loopStack.pop();
                    if(top[0].equals("FOR")){
                        String v=top[1]; String upd=top[2]; String lblLoop=top[3]; String lblEnd=top[4];
                        if(upd.equals(v+"++")||upd.equals(v+" ++")){
                            code.add(new Bytecode.Instr(Bytecode.Op.LOAD, v));
                            code.add(new Bytecode.Instr(Bytecode.Op.PUSH, 1));
                            code.add(new Bytecode.Instr(Bytecode.Op.ADD));
                            code.add(new Bytecode.Instr(Bytecode.Op.STORE, v));
                        } else if(upd.matches(v+"\\s*=.*")){
                            String expr=upd.replaceFirst(v+"\\s*=\\s*","");
                            compileExpr(expr, code); code.add(new Bytecode.Instr(Bytecode.Op.STORE, v));
                        } else {
                            compileExpr(upd, code);
                            code.add(new Bytecode.Instr(Bytecode.Op.POP));
                        }
                        code.add(new Bytecode.Instr(Bytecode.Op.JMP, lblLoop));
                        code.add(new Bytecode.Instr(Bytecode.Op.LABEL, lblEnd));
                    } else if(top[0].equals("WHILE")){
                        String lblLoop=top[1]; String lblEnd=top[2];
                        code.add(new Bytecode.Instr(Bytecode.Op.JMP, lblLoop));
                        code.add(new Bytecode.Instr(Bytecode.Op.LABEL, lblEnd));
                    } else if(top[0].equals("FOREACH")){
                        String idxVar=top[3]; String lblLoop=top[4]; String lblEnd=top[5];
                        code.add(new Bytecode.Instr(Bytecode.Op.LOAD, idxVar));
                        code.add(new Bytecode.Instr(Bytecode.Op.PUSH, 1));
                        code.add(new Bytecode.Instr(Bytecode.Op.ADD));
                        code.add(new Bytecode.Instr(Bytecode.Op.STORE, idxVar));
                        code.add(new Bytecode.Instr(Bytecode.Op.JMP, lblLoop));
                        code.add(new Bytecode.Instr(Bytecode.Op.LABEL, lblEnd));
                    }
                    continue;
                } else if(topType.equals("IF")){
                    blockStack.pop();
                    String[] ctx = ifStack.pop();
                    if(ctx[2].equals("then")){
                        code.add(new Bytecode.Instr(Bytecode.Op.LABEL, ctx[0]));
                        if(!ctx[0].equals(ctx[1])) code.add(new Bytecode.Instr(Bytecode.Op.LABEL, ctx[1]));
                    } else {
                        code.add(new Bytecode.Instr(Bytecode.Op.LABEL, ctx[1]));
                    }
                    continue;
                } else if(topType.equals("MATCH")){
                    blockStack.pop();
                    if(!matchStack.isEmpty()){
                        String lblEnd = matchStack.pop();
                        code.add(new Bytecode.Instr(Bytecode.Op.POP));
                        code.add(new Bytecode.Instr(Bytecode.Op.LABEL, lblEnd));
                    }
                    continue;
                } else if(topType.equals("DO")){
                    // } для do — ждем while, не закрываем
                    continue;
                } else if(topType.equals("LAMBDA")){
                    blockStack.pop();
                    // уже обработано выше
                    continue;
                }
                continue;
            }
            // обработка } else {  и  } elif ... {
            if(line.startsWith("} else")||line.equals("else")||line.startsWith("else {")){
                if(!ifStack.isEmpty()){
                    String[] ctx = ifStack.pop();
                    code.add(new Bytecode.Instr(Bytecode.Op.JMP, ctx[1]));
                    code.add(new Bytecode.Instr(Bytecode.Op.LABEL, ctx[0]));
                    // теперь мы в else
                    ifStack.push(new String[]{ctx[1], ctx[1], "else"});
                }
                continue;
            }
            if(line.startsWith("} elif")||line.startsWith("elif ")){
                String cond;
                if(line.startsWith("} elif")) cond=line.replaceFirst("\\}\\s*elif\\s*","").replaceAll("\\s*\\{\\s*$","").trim();
                else cond=line.replaceFirst("elif\\s*","").replaceAll("\\s*\\{\\s*$","").trim();
                if(!ifStack.isEmpty()){
                    String[] prev = ifStack.pop();
                    code.add(new Bytecode.Instr(Bytecode.Op.JMP, prev[1]));
                    code.add(new Bytecode.Instr(Bytecode.Op.LABEL, prev[0]));
                    String newElse="Lelse"+(labelId++);
                    compileExpr(cond, code);
                    code.add(new Bytecode.Instr(Bytecode.Op.CJMP, newElse));
                    ifStack.push(new String[]{newElse, prev[1], "then"});
                }
                continue;
            }

            // if / elif / else однострочный без } (из-за expand они уже разделены, но оставим)
            if(line.startsWith("if ")||line.startsWith("if(")){
                // поддержка if cond -> stmt
                if(line.contains("->")){
                    String[] parts=line.split("->",2);
                    String condPart=parts[0].replaceFirst("^if\\s*\\(?","").trim();
                    condPart=condPart.replaceAll("\\)?\\s*$","").trim();
                    String stmt=parts[1].trim().replaceAll(";\\s*$","").trim();
                    String end="Lend"+(labelId++);
                    compileExpr(condPart, code);
                    code.add(new Bytecode.Instr(Bytecode.Op.CJMP, end));
                    // stmt может быть print(...)
                    if(stmt.startsWith("print")){
                        Matcher pm = Pattern.compile("print\\s*\\(\\s*\"([^\"]*)\"\\s*\\)").matcher(stmt);
                        Matcher pm2 = Pattern.compile("print\\s*\\(\\s*(.+)\\s*\\)").matcher(stmt);
                        if(pm.find()){
                            String inner=pm.group(1);
                            // интерполяция как в print
                            Matcher im=Pattern.compile("\\{([^}]+)\\}").matcher(inner);
                            int last=0; boolean has=false; List<String> prts=new ArrayList<>();
                            while(im.find()){ has=true; if(im.start()>last) prts.add("STR:"+inner.substring(last,im.start())); prts.add("EXPR:"+im.group(1).trim()); last=im.end(); }
                            if(last<inner.length()) prts.add("STR:"+inner.substring(last));
                            if(!has){ code.add(new Bytecode.Instr(Bytecode.Op.PUSH, inner)); code.add(new Bytecode.Instr(Bytecode.Op.PRINTLN)); }
                            else { boolean first=true; for(String prt:prts){ if(prt.startsWith("STR:")) code.add(new Bytecode.Instr(Bytecode.Op.PUSH, prt.substring(4))); else compileExpr(prt.substring(5), code); if(!first) code.add(new Bytecode.Instr(Bytecode.Op.ADD)); first=false; } code.add(new Bytecode.Instr(Bytecode.Op.PRINTLN)); }
                        } else if(pm2.find()){ compileExpr(pm2.group(1), code); code.add(new Bytecode.Instr(Bytecode.Op.PRINTLN)); }
                    } else {
                        compileExpr(stmt, code); code.add(new Bytecode.Instr(Bytecode.Op.POP));
                    }
                    code.add(new Bytecode.Instr(Bytecode.Op.LABEL, end));
                    continue;
                }
                String cond = extractCond(line);
                String elseLbl = "Lelse"+(labelId++);
                String endLbl = "Lend"+(labelId++);
                compileExpr(cond, code);
                code.add(new Bytecode.Instr(Bytecode.Op.CJMP, elseLbl));
                ifStack.push(new String[]{elseLbl, endLbl, "then"});
                blockStack.push("IF");
                // если после { есть тело на той же строке (уже развернуто через expand, но на всякий)
                String after=afterBrace(line);
                if(!after.isEmpty() && !after.equals("}")){
                    // тело может содержать несколько стейтментов
                    for(String s: splitBodyStatements(after)){
                        q.addFirst(s);
                        // но нужно сохранить порядок — добавляем в обратном
                    }
                    // Чтобы сохранить порядок, добавляем в q в обратном порядке
                    // Уже сделали, но упростим: просто компилируем сразу
                    // Уберем и сделаем прямой compile
                    // Откатим — проще: компилируем after сразу
                    // (expand уже разносит, так что after обычно пустой)
                }
                continue;
            }

            // print("... {expr} ...")
            if(line.startsWith("print")){
                // обработка остатка после print как отдельный стейтмент (для строк вида print(..) w=w-1)
                int open=line.indexOf("(");
                int close=findMatchingParen(line, open);
                String stmtPart;
                String rest="";
                if(open!=-1 && close!=-1){
                    stmtPart=line.substring(0,close+1);
                    rest=line.substring(close+1).trim();
                    if(rest.startsWith(";")) rest=rest.substring(1).trim();
                    if(!rest.isEmpty()){
                        q.addFirst(rest);
                    }
                    line=stmtPart;
                }
                Matcher pm = Pattern.compile("print\\s*\\(\\s*\"((?:\\\\\"|[^\"])*)\"\\s*\\)").matcher(line);
                Matcher pm2 = Pattern.compile("print\\s*\\(\\s*(.+)\\s*\\)").matcher(line);
                if(pm.find() && pm.group(0).equals(line)){
                    String inner = pm.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
                    List<String> parts = new ArrayList<>();
                    Matcher im = Pattern.compile("\\{([^}]+)\\}").matcher(inner);
                    int last=0;
                    boolean hasInterp=false;
                    while(im.find()){
                        hasInterp=true;
                        if(im.start()>last) parts.add("STR:"+ inner.substring(last, im.start()));
                        String exprInside = im.group(1).trim().replace("\\\"", "\"");
                        parts.add("EXPR:"+exprInside);
                        last=im.end();
                    }
                    if(last<inner.length()) parts.add("STR:"+inner.substring(last));
                    if(!hasInterp){
                        code.add(new Bytecode.Instr(Bytecode.Op.PUSH, inner, raw));
                        code.add(new Bytecode.Instr(Bytecode.Op.PRINTLN));
                    } else {
                        boolean first=true;
                        for(String part: parts){
                            if(part.startsWith("STR:")) code.add(new Bytecode.Instr(Bytecode.Op.PUSH, part.substring(4)));
                            else {
                                String expr = part.substring(5);
                                compileExpr(expr, code);
                            }
                            if(!first) code.add(new Bytecode.Instr(Bytecode.Op.ADD));
                            first=false;
                        }
                        code.add(new Bytecode.Instr(Bytecode.Op.PRINTLN));
                    }
                } else if(pm2.find()){
                    String expr = pm2.group(1).trim();
                    // если expr уже в кавычках без интерполяции — обработка выше не сработала из-за { внутри?
                    // пробуем интерполяцию внутри кавычек даже для pm2
                    if(expr.startsWith("\"") && expr.endsWith("\"")){
                        String inner=expr.substring(1,expr.length()-1);
                        if(inner.contains("{")){
                            List<String> parts = new ArrayList<>();
                            Matcher im = Pattern.compile("\\{([^}]+)\\}").matcher(inner);
                            int last=0; boolean has=false;
                            while(im.find()){ has=true; if(im.start()>last) parts.add("STR:"+inner.substring(last,im.start())); parts.add("EXPR:"+im.group(1).trim()); last=im.end(); }
                            if(last<inner.length()) parts.add("STR:"+inner.substring(last));
                            if(has){
                                boolean first=true;
                                for(String part: parts){
                                    if(part.startsWith("STR:")) code.add(new Bytecode.Instr(Bytecode.Op.PUSH, part.substring(4)));
                                    else compileExpr(part.substring(5), code);
                                    if(!first) code.add(new Bytecode.Instr(Bytecode.Op.ADD));
                                    first=false;
                                }
                                code.add(new Bytecode.Instr(Bytecode.Op.PRINTLN));
                                continue;
                            }
                        }
                        code.add(new Bytecode.Instr(Bytecode.Op.PUSH, inner));
                        code.add(new Bytecode.Instr(Bytecode.Op.PRINTLN));
                    } else {
                        compileExpr(expr, code);
                        code.add(new Bytecode.Instr(Bytecode.Op.PRINTLN));
                    }
                }
                continue;
            }
            // var / const / типы
            Matcher vm = Pattern.compile("(?:var\\s+(?:\\w+\\s+)?(\\w+)\\s*=\\s*(.+))|(?:const\\s+(?:\\w+\\s+)?(\\w+)\\s*=\\s*(.+))|(?:\\b(?:str|flt|dbl|chr|bool|lng)\\s+(\\w+)\\s*=\\s*(.+))").matcher(line);
            if(vm.find()){
                String name = vm.group(1)!=null? vm.group(1): vm.group(3)!=null? vm.group(3): vm.group(5);
                String expr = vm.group(2)!=null? vm.group(2): vm.group(4)!=null? vm.group(4): vm.group(6);
                if(name!=null && expr!=null){
                    expr = expr.replaceAll(";$","").trim();
                    // обработка хвоста после ; или второго стейтмента
                    // если expr содержит ; то разделим
                    if(expr.contains(";")){
                        String[] segs=expr.split(";",2);
                        expr=segs[0].trim();
                        String rest=segs[1].trim();
                        if(!rest.isEmpty()) q.addFirst(rest);
                    }
                    compileExpr(expr, code);
                    code.add(new Bytecode.Instr(Bytecode.Op.STORE, name, raw));
                    // проверить остаток в line после var ... (например var x=1 w=2)
                    // уже через splitBody?
                    continue;
                }
            }
            // выражение как statement: box.set("hi"), lst.add("x"), mp.put("k","v"), write(...), read(...)
            if(line.matches(".*\\.\\w+\\s*\\(.*\\).*") && !line.startsWith("print") && !line.startsWith("if") && !line.startsWith("for") && !line.startsWith("while")){
                String exprStmt = line.replaceAll(";$","").trim();
                // если несколько стейтментов в одной строке через пробел без ; — split
                // проверим есть ли второй стейтмент после )
                int open=exprStmt.indexOf("(");
                if(open!=-1){
                    int close=findMatchingParen(exprStmt, open);
                    if(close!=-1 && close+1 < exprStmt.length()){
                        String rest=exprStmt.substring(close+1).trim();
                        if(rest.startsWith(";")) rest=rest.substring(1).trim();
                        if(!rest.isEmpty()){
                            q.addFirst(rest);
                            exprStmt=exprStmt.substring(0,close+1).trim();
                        }
                    }
                }
                compileExpr(exprStmt, code);
                code.add(new Bytecode.Instr(Bytecode.Op.POP));
                continue;
            }
            if(line.matches("\\w+\\s*\\(.*\\).*") && !line.startsWith("print") && !line.startsWith("if") && !line.startsWith("for") && !line.startsWith("while") && !line.contains("=")){
                String exprStmt = line.replaceAll(";$","").trim();
                int open=exprStmt.indexOf("(");
                if(open!=-1){
                    int close=findMatchingParen(exprStmt, open);
                    if(close!=-1 && close+1 < exprStmt.length()){
                        String rest=exprStmt.substring(close+1).trim();
                        if(!rest.isEmpty()) q.addFirst(rest);
                        exprStmt=exprStmt.substring(0,close+1).trim();
                    }
                }
                compileExpr(exprStmt, code);
                code.add(new Bytecode.Instr(Bytecode.Op.POP));
                continue;
            }
            // присвоение с полем или индексом: win.w = ..., arr[i] = ..., win.pixels[i] = ...
            if(line.contains("=") && !line.startsWith("if") && !line.startsWith("for") && !line.startsWith("while") && !line.contains("==") && !line.contains("!=") && !line.contains("<=") && !line.contains(">=")){
                String ll = line.replaceAll(";$","").trim();
                // найти = вне строк и скобок
                int eqPos=-1;
                boolean inStr=false; char strCh=0; boolean esc=false; int depth=0;
                for(int i=0;i<ll.length();i++){
                    char c=ll.charAt(i);
                    if(esc){ esc=false; continue; }
                    if(c=='\\'){ esc=true; continue; }
                    if(!inStr && (c=='"'||c=='\'')){ inStr=true; strCh=c; continue; }
                    else if(inStr && c==strCh){ inStr=false; continue; }
                    if(inStr) continue;
                    if(c=='('||c=='[') depth++;
                    else if(c==')'||c==']') depth--;
                    if(depth==0 && c=='=' && i+1<ll.length() && ll.charAt(i+1)!='=' && (i==0 || ll.charAt(i-1)!='=' && ll.charAt(i-1)!='!' && ll.charAt(i-1)!='<' && ll.charAt(i-1)!='>')){
                        eqPos=i; break;
                    }
                }
                if(eqPos!=-1){
                    String leftPart = ll.substring(0,eqPos).trim();
                    String rightPart = ll.substring(eqPos+1).trim();
                    if(leftPart.contains("[") && leftPart.contains("]")){
                        int open=leftPart.indexOf("[");
                        int close=leftPart.lastIndexOf("]");
                        if(open!=-1 && close!=-1 && close>open){
                            String base = leftPart.substring(0,open).trim();
                            String idx = leftPart.substring(open+1, close).trim();
                            String restIdx = leftPart.substring(close+1).trim();
                            if(restIdx.isEmpty() || restIdx.equals("")){
                                compileExpr(base, code);
                                compileExpr(idx, code);
                                compileExpr(rightPart, code);
                                code.add(new Bytecode.Instr(Bytecode.Op.CALL, "setAt"));
                                code.add(new Bytecode.Instr(Bytecode.Op.POP));
                                continue;
                            }
                        }
                    } else if(leftPart.contains(".")){
                        int dot = leftPart.lastIndexOf(".");
                        String obj = leftPart.substring(0,dot).trim();
                        String field = leftPart.substring(dot+1).trim();
                        if(field.matches("\\w+") && obj.matches("[a-zA-Z_][a-zA-Z0-9_\\.]*")){
                            compileExpr(obj, code);
                            code.add(new Bytecode.Instr(Bytecode.Op.PUSH, field));
                            compileExpr(rightPart, code);
                            code.add(new Bytecode.Instr(Bytecode.Op.CALL, "put"));
                            code.add(new Bytecode.Instr(Bytecode.Op.POP));
                            continue;
                        }
                    }
                }
            }
            // name = expr (переприсваивание) — может быть несколько через пробел?
            Matcher am = Pattern.compile("(\\w+)\\s*=\\s*(.+)").matcher(line);
            if(am.matches() && !line.startsWith("if") && !line.startsWith("for") && !line.startsWith("while")){
                String name = am.group(1);
                String expr = am.group(2).replaceAll(";$","").trim();
                // если expr содержит второй стейтмент после ; или без ; но с var/print?
                if(expr.contains(";")){
                    String[] segs=expr.split(";",2);
                    expr=segs[0].trim();
                    String rest=segs[1].trim();
                    if(!rest.isEmpty()) q.addFirst(rest);
                } else {
                    // проверить есть ли второй стейтмент после выражения (например w = w - 1 print(...))
                    // Ищем границу выражения: найдем где заканчивается expr (до пробела + слово + =)
                    // Проще: если line была "w = w - 1 print(...)" — но такое редко
                }
                compileExpr(expr, code);
                code.add(new Bytecode.Instr(Bytecode.Op.STORE, name));
                continue;
            }
            // ret
            if(line.startsWith("ret")){
                String expr = line.replaceFirst("ret","").trim().replaceAll(";$","");
                if(!expr.isEmpty()){ compileExpr(expr, code); code.add(new Bytecode.Instr(Bytecode.Op.RET)); }
                else code.add(new Bytecode.Instr(Bytecode.Op.RET));
                continue;
            }
            // sleep/exit как отдельный стейтмент без var
            if(line.matches("sleep\\s*\\(.*\\)")||line.matches("exit.*")){
                compileExpr(line.replaceAll(";",""), code);
                code.add(new Bytecode.Instr(Bytecode.Op.POP));
                continue;
            }
        }
        code.add(new Bytecode.Instr(Bytecode.Op.EXIT));
        return code;
    }

    static void compileExpr(String expr, List<Bytecode.Instr> code){
        expr=expr.trim();
        if(expr.isEmpty()) return;
        // убираем лишние скобки вокруг всего выражения: (a+b)
        if(expr.startsWith("(") && expr.endsWith(")")){
            int d=0; boolean inStr=false; char sc=0; boolean esc=false; boolean balanced=true;
            for(int i=0;i<expr.length();i++){
                char c=expr.charAt(i);
                if(esc){ esc=false; continue; }
                if(c=='\\'){ esc=true; continue; }
                if(!inStr && (c=='"'||c=='\'')){ inStr=true; sc=c; }
                else if(inStr && c==sc) inStr=false;
                else if(!inStr){
                    if(c=='(') d++;
                    else if(c==')') d--;
                    if(d<0) { balanced=false; break; }
                }
            }
            if(balanced && d==0){
                // проверим, что внешние скобки действительно обрамляют всё
                String inner=expr.substring(1,expr.length()-1).trim();
                // избегаем бесконечной рекурсии для "(a)" -> "a"
                if(!inner.isEmpty() && !inner.equals(expr)) {
                    // но только если inner не пусто и не то же
                    // для безопасности, если inner содержит сбалансированные скобки, рекурсивно
                    // Проверим, что количество скобок внутри сбалансировано
                    compileExpr(inner, code);
                    return;
                }
            }
        }
        if(expr.contains("??")){
            // null-coalesce: a ?? b  => a != null ? a : b
            // Реализуем через DUP + проверку null
            int idx=expr.indexOf("??");
            String left=expr.substring(0,idx).trim();
            String right=expr.substring(idx+2).trim();
            // left ?? right  -> DUP left, CJMP, POP
            compileExpr(left, code);
            code.add(new Bytecode.Instr(Bytecode.Op.DUP));
            String lblUseRight="LcoalesceR"+System.nanoTime();
            String lblEnd="LcoalesceE"+System.nanoTime();
            code.add(new Bytecode.Instr(Bytecode.Op.PUSH, (Object)null));
            code.add(new Bytecode.Instr(Bytecode.Op.NE));
            // Но NE даст bool, CJMP прыгает если false (т.е. left==null)
            code.add(new Bytecode.Instr(Bytecode.Op.CJMP, lblUseRight));
            // left != null, оставляем left, прыгаем к end
            code.add(new Bytecode.Instr(Bytecode.Op.JMP, lblEnd));
            code.add(new Bytecode.Instr(Bytecode.Op.LABEL, lblUseRight));
            code.add(new Bytecode.Instr(Bytecode.Op.POP));
            compileExpr(right, code);
            code.add(new Bytecode.Instr(Bytecode.Op.LABEL, lblEnd));
            return;
        }
        if(expr.contains("?.")){
            expr = expr.replace("?.", ".");
        }
        // тернарный a ? b : c  — найдем ? и : вне строк/скобок
        int qPos=-1, colonPos=-1;
        {
            boolean inStr=false; char sc=0; boolean esc=false; int depth=0;
            for(int i=0;i<expr.length();i++){
                char c=expr.charAt(i);
                if(esc){ esc=false; continue; }
                if(c=='\\'){ esc=true; continue; }
                if(!inStr && (c=='"'||c=='\'')){ inStr=true; sc=c; }
                else if(inStr && c==sc) inStr=false;
                else if(!inStr){
                    if(c=='('||c=='[') depth++;
                    else if(c==')'||c==']') depth--;
                    else if(depth==0 && c=='?' && qPos==-1) qPos=i;
                    else if(depth==0 && c==':' && qPos!=-1){ colonPos=i; break; }
                }
            }
            if(qPos!=-1 && colonPos!=-1){
                String cond=expr.substring(0,qPos).trim();
                String left=expr.substring(qPos+1, colonPos).trim();
                String right=expr.substring(colonPos+1).trim();
                String lblElse="LternElse"+code.size();
                String lblEnd="LternEnd"+code.size();
                compileExpr(cond, code);
                code.add(new Bytecode.Instr(Bytecode.Op.CJMP, lblElse));
                compileExpr(left, code);
                code.add(new Bytecode.Instr(Bytecode.Op.JMP, lblEnd));
                code.add(new Bytecode.Instr(Bytecode.Op.LABEL, lblElse));
                compileExpr(right, code);
                code.add(new Bytecode.Instr(Bytecode.Op.LABEL, lblEnd));
                return;
            }
        }
        // массив [1,2,3] или ["Анна","Иван"] или [[1,2],[3,4]]
        if(expr.startsWith("[") && expr.endsWith("]")){
            String inner = expr.substring(1, expr.length()-1).trim();
            if(inner.isEmpty()){
                code.add(new Bytecode.Instr(Bytecode.Op.PUSH, new ArrayList<>()));
                return;
            }
            // Проверим, что это действительно массив, а не индекс nums[0]
            // Индекс содержит ] не в конце? Для nums[0] expr = "nums[0]" — не начинается с [
            List<Object> list = parseArrayElements(inner);
            code.add(new Bytecode.Instr(Bytecode.Op.PUSH, list));
            return;
        }
        // индекс arr[0] -> getAt (поддержка win.pixels[0] и тп)
        if(expr.matches(".*\\[.+\\].*") && expr.contains("[") && expr.endsWith("]")){
            int open=expr.indexOf("[");
            int close=expr.lastIndexOf("]");
            String base=expr.substring(0,open).trim();
            String idxExpr=expr.substring(open+1,close).trim();
            // проверка что base — переменная/поле, не массив литерал
            if(base.matches("[a-zA-Z_][a-zA-Z0-9_\\.]*")){
                compileExpr(base, code);
                compileExpr(idxExpr, code);
                code.add(new Bytecode.Instr(Bytecode.Op.CALL, "getAt"));
                // цепочка индексов matrix[0][1] ?
                String rest=expr.substring(close+1).trim();
                if(!rest.isEmpty()){
                    // matrix[0][1] -> после первого getAt, rest = "[1]"
                    // Рекурсивно
                    // Но проще: если rest начинается с [, то это второй индекс
                    // Мы могли бы поддержать, но пока один уровень
                }
                return;
            }
        }
        // i++ / i-- как expr
        if(expr.matches("\\w+\\+\\+")){ String v=expr.replace("++","").trim(); code.add(new Bytecode.Instr(Bytecode.Op.LOAD, v)); code.add(new Bytecode.Instr(Bytecode.Op.PUSH, 1)); code.add(new Bytecode.Instr(Bytecode.Op.ADD)); code.add(new Bytecode.Instr(Bytecode.Op.STORE, v)); code.add(new Bytecode.Instr(Bytecode.Op.LOAD, v)); return; }
        if(expr.matches("\\w+--")){ String v=expr.replace("--","").trim(); code.add(new Bytecode.Instr(Bytecode.Op.LOAD, v)); code.add(new Bytecode.Instr(Bytecode.Op.PUSH, 1)); code.add(new Bytecode.Instr(Bytecode.Op.SUB)); code.add(new Bytecode.Instr(Bytecode.Op.STORE, v)); code.add(new Bytecode.Instr(Bytecode.Op.LOAD, v)); return; }
        // отрицание -x  (унарный минус)
        if(expr.startsWith("-") && expr.length()>1){
            String rest=expr.substring(1).trim();
            if(rest.matches("[a-zA-Z_].*") || rest.matches("\\d+.*") || rest.startsWith("(") || rest.startsWith("\"") || rest.startsWith("'")){
                compileExpr(rest, code);
                code.add(new Bytecode.Instr(Bytecode.Op.NEG));
                return;
            }
        }
        if(expr.startsWith("!")){
            String rest=expr.substring(1).trim();
            compileExpr(rest, code);
            code.add(new Bytecode.Instr(Bytecode.Op.NOT));
            return;
        }
        // строки с интерполяцией "a {b} c"
        if((expr.startsWith("\"") && expr.endsWith("\"") && expr.length()>=2) || (expr.startsWith("'") && expr.endsWith("'") && expr.length()>=2)){
            String inner=stripQuotes(expr);
            if(inner.contains("{") && inner.contains("}")){
                // интерполяция как в print: "a {b} c" -> "a " + b + " c"
                List<String> parts=new ArrayList<>();
                Matcher im=Pattern.compile("\\{([^}]+)\\}").matcher(inner);
                int last=0; boolean has=false;
                while(im.find()){
                    has=true;
                    if(im.start()>last) parts.add("STR:"+inner.substring(last, im.start()));
                    String eInside=im.group(1).trim().replace("\\\"", "\"");
                    parts.add("EXPR:"+eInside);
                    last=im.end();
                }
                if(last<inner.length()) parts.add("STR:"+inner.substring(last));
                if(has){
                    boolean first=true;
                    for(String part: parts){
                        if(part.startsWith("STR:")) code.add(new Bytecode.Instr(Bytecode.Op.PUSH, part.substring(4)));
                        else compileExpr(part.substring(5), code);
                        if(!first) code.add(new Bytecode.Instr(Bytecode.Op.ADD));
                        first=false;
                    }
                    return;
                }
            }
            code.add(new Bytecode.Instr(Bytecode.Op.PUSH, inner));
            return;
        }
        // hex 0xFF00FF поддержка для цветов 3D/2D
        if(expr.matches("-?0[xX][0-9a-fA-F]+")){
            String t = expr.replaceFirst("^-","").replaceFirst("0[xX]","");
            boolean neg = expr.startsWith("-");
            try{
                long v = Long.parseLong(t, 16);
                if(neg) v = -v;
                if(v>=Integer.MIN_VALUE && v<=Integer.MAX_VALUE) code.add(new Bytecode.Instr(Bytecode.Op.PUSH, (int)v));
                else code.add(new Bytecode.Instr(Bytecode.Op.PUSH, v));
            }catch(Exception e){ code.add(new Bytecode.Instr(Bytecode.Op.PUSH, expr)); }
            return;
        }
        // char literal 'A' уже обработан выше, но на всякий
        // числа с суффиксами 100L 10.5f 10.5d
        if(expr.matches("-?\\d+[lL]")){
            String t=expr.replaceAll("[lL]$","");
            try{ long v=Long.parseLong(t); if(v>=Integer.MIN_VALUE && v<=Integer.MAX_VALUE) code.add(new Bytecode.Instr(Bytecode.Op.PUSH, (int)v)); else code.add(new Bytecode.Instr(Bytecode.Op.PUSH, v)); }catch(Exception e){ code.add(new Bytecode.Instr(Bytecode.Op.PUSH, t)); }
            return;
        }
        if(expr.matches("-?\\d+")){ code.add(new Bytecode.Instr(Bytecode.Op.PUSH, Integer.parseInt(expr))); return; }
        if(expr.matches("-?\\d+\\.\\d+[fFdD]?")||expr.matches("-?\\d+\\.\\d*")||expr.matches("-?\\d*\\.\\d+")){
            String t=expr.replaceAll("[fFdDdDlL]$","");
            try{ code.add(new Bytecode.Instr(Bytecode.Op.PUSH, Double.parseDouble(t))); }catch(Exception e){ code.add(new Bytecode.Instr(Bytecode.Op.PUSH, t)); }
            return;
        }
        if(expr.matches("-?\\d+\\.\\d+[fF]")){ code.add(new Bytecode.Instr(Bytecode.Op.PUSH, Float.parseFloat(expr.replaceAll("[fF]$","")))); return; }
        if(expr.equals("true")||expr.equals("false")){ code.add(new Bytecode.Instr(Bytecode.Op.PUSH, Boolean.parseBoolean(expr))); return; }
        if(expr.equals("null")){ code.add(new Bytecode.Instr(Bytecode.Op.PUSH, (Object)null)); return; }
        // лямбда (a,b) -> a+b  (стрелочная)
        if(expr.matches("\\(.*\\)\\s*->.*") || expr.matches("\\w+\\s*->.*")){
            // var fn = (a,b) -> a + b  -> уже обрабатывается как var, но если expr сам по себе — создадим лямбду?
            // Для простоты: (a,b) -> a+b  -> PUSH label, STORE?
            // Пока не поддерживаем inline лямбду как значение, обработаем как константу 0
            // Но var fn = (a,b) -> ...  обрабатывается в compileToMvm как var, где expr = "(a,b) -> a + b"
            // Здесь мы находимся в compileExpr для expr "(a,b) -> a + b"
            // Мы можем сгенерировать лямбду-аноним?
            // Упростим: создадим уникальный label
            Matcher lm = Pattern.compile("\\(?\\s*([^)]*)\\s*\\)?\\s*->\\s*(.+)").matcher(expr);
            if(lm.matches()){
                String params=lm.group(1).trim();
                String body=lm.group(2).trim();
                // создадим лямбду с уникальным именем
                String anon="__lambda"+code.size();
                // Но compileToMvm ожидает lambdaStack — сложная интеграция
                // Для var fn = (a,b) -> a+b  мы можем просто развернуть в lambda get?
                // Простейший фикс: если expr это "(a,b) -> a + b" и мы в контексте var, то var уже вызвал compileExpr, здесь мы можем просто сгенерировать код для body как функцию?
                // Упростим: вернем PUSH 0 и пусть MVM вернет 0 — но это ломает семантику
                // Правильный фикс: сгенерировать inline лямбду через JMP/LABEL
                // Реализуем:
                String lblLambda="Llambda_anon"+code.size();
                String lblEnd="Lend_anon"+code.size();
                code.add(new Bytecode.Instr(Bytecode.Op.JMP, lblEnd));
                code.add(new Bytecode.Instr(Bytecode.Op.LABEL, lblLambda));
                // параметры -> STORE в обратном порядке
                if(!params.isEmpty()){
                    String[] ps=params.split(",");
                    for(int i=ps.length-1;i>=0;i--){
                        String pn=ps[i].trim().split("\\s+")[0].trim();
                        // убрать тип
                        String[] toks=pn.split("\\s+");
                        pn=toks[toks.length-1];
                        if(!pn.isEmpty()) code.add(new Bytecode.Instr(Bytecode.Op.STORE, pn));
                    }
                }
                // тело: если body содержит { ret ... } или просто expr
                if(body.startsWith("{") && body.endsWith("}")){
                    String innerBody=body.substring(1,body.length()-1).trim();
                    if(innerBody.startsWith("ret")){ compileExpr(innerBody.replaceFirst("ret","").trim(), code); code.add(new Bytecode.Instr(Bytecode.Op.RET)); }
                    else { compileExpr(innerBody, code); code.add(new Bytecode.Instr(Bytecode.Op.RET)); }
                } else {
                    // body это выражение
                    if(body.startsWith("ret")){ compileExpr(body.replaceFirst("ret","").trim(), code); code.add(new Bytecode.Instr(Bytecode.Op.RET)); }
                    else { compileExpr(body, code); code.add(new Bytecode.Instr(Bytecode.Op.RET)); }
                }
                code.add(new Bytecode.Instr(Bytecode.Op.LABEL, lblEnd));
                code.add(new Bytecode.Instr(Bytecode.Op.PUSH, lblLambda));
                return;
            }
        }
        // Box, Map, func — до бинарных, чтобы < > generics не разобрались как сравнение
        if(expr.matches("Box<.*>\\(\\)")||expr.equals("Box()")){
            code.add(new Bytecode.Instr(Bytecode.Op.CALL, "Box"));
            return;
        }
        if(expr.matches("\\w+\\s*\\(.*\\)") || expr.matches("\\w+<.*>\\s*\\(.*\\)")){
            Matcher fmEarly = Pattern.compile("(\\w+)(?:<.*>)?\\s*\\((.*)\\)", Pattern.DOTALL).matcher(expr);
            if(fmEarly.matches()){
                String fn=fmEarly.group(1); String args=fmEarly.group(2);
                // убедимся, что это не метод obj.method() — там есть точка, уже обработано выше? методы с точкой уже вернули бы
                // Проверим, что expr не содержит точки перед (
                if(!expr.contains(".")){
                    List<String> parts=splitArgsRespectingStringsAndBrackets(args);
                    for(String p: parts) if(!p.trim().isEmpty()) compileExpr(p.trim(), code);
                    switch(fn){
                        case "sum": for(int i=1;i<parts.size();i++) if(!parts.get(i).trim().isEmpty()) code.add(new Bytecode.Instr(Bytecode.Op.ADD)); break;
                        case "sub": code.add(new Bytecode.Instr(Bytecode.Op.SUB)); break;
                        case "mult": code.add(new Bytecode.Instr(Bytecode.Op.MUL)); break;
                        case "div": code.add(new Bytecode.Instr(Bytecode.Op.DIV)); break;
                        default: code.add(new Bytecode.Instr(Bytecode.Op.CALL, fn)); break;
                    }
                    return;
                }
            }
        }
        if(expr.startsWith("{") && expr.endsWith("}") && expr.contains(":")){
            String inner=expr.substring(1,expr.length()-1).trim();
            // проверим, что это Map, а не блок: содержит : и " 
            if(inner.contains(":")){
                code.add(new Bytecode.Instr(Bytecode.Op.CALL, "Map"));
                String storeVar="__mapTmp"+code.size();
                code.add(new Bytecode.Instr(Bytecode.Op.STORE, storeVar));
                List<String> pairs=splitArgsRespectingStringsAndBrackets(inner);
                for(String pair: pairs){
                    int colon=pair.indexOf(":");
                    if(colon==-1) continue;
                    String k=pair.substring(0,colon).trim();
                    String v=pair.substring(colon+1).trim();
                    k=stripQuotes(k);
                    code.add(new Bytecode.Instr(Bytecode.Op.LOAD, storeVar));
                    code.add(new Bytecode.Instr(Bytecode.Op.PUSH, k));
                    compileExpr(v, code);
                    code.add(new Bytecode.Instr(Bytecode.Op.CALL, "put"));
                    code.add(new Bytecode.Instr(Bytecode.Op.POP));
                }
                code.add(new Bytecode.Instr(Bytecode.Op.LOAD, storeVar));
                return;
            }
        }
        // бинарные операции с правильным приоритетом: ищем оператор вне строк/скобок с наименьшим приоритетом
        String[] opsByPrio = {"||","&&","==","!=","<=",">=","<",">","+","-","*","/","%","^"};
        // Найдем оператор с наименьшим приоритетом (самый внешний)
        int bestPos=-1; String bestOp=null; int bestPrio=Integer.MAX_VALUE;
        boolean inStr=false; char sc=0; boolean esc=false; int depth=0;
        for(int i=0;i<expr.length();i++){
            char c=expr.charAt(i);
            if(esc){ esc=false; continue; }
            if(c=='\\'){ esc=true; continue; }
            if(!inStr && (c=='"'||c=='\'')){ inStr=true; sc=c; continue; }
            else if(inStr && c==sc){ inStr=false; continue; }
            if(inStr) continue;
            if(c=='('||c=='[') depth++;
            else if(c==')'||c==']') depth--;
            if(depth!=0) continue;
            for(int p=0;p<opsByPrio.length;p++){
                String op=opsByPrio[p];
                if(expr.startsWith(op, i)){
                    // проверим, что op не часть большего (например < и <=)
                    if(op.equals("<") && i+1<expr.length() && expr.charAt(i+1)=='=') continue;
                    if(op.equals(">") && i+1<expr.length() && expr.charAt(i+1)=='=') continue;
                    if(op.equals("=") && i+1<expr.length() && expr.charAt(i+1)=='=') continue; // часть ==
                    if(op.equals("!") && i+1<expr.length() && expr.charAt(i+1)=='=') continue;
                    // приоритет: чем меньше p, тем ниже приоритет? Мы хотим самый низкий приоритет (||) вне
                    // opsByPrio упорядочен от низкого к высокому? Сейчас || (0) низкий, * (11) высокий
                    // Нам нужен оператор с минимальным p (самый низкий) как главный
                    if(p < bestPrio){
                        bestPrio=p; bestPos=i; bestOp=op;
                    }
                    // для одинакового приоритета — берем последний (правоассоциативность?) для + - хотим последний
                    else if(p==bestPrio){
                        bestPos=i; bestOp=op;
                    }
                }
            }
        }
        if(bestPos!=-1){
            String left=expr.substring(0,bestPos).trim();
            String right=expr.substring(bestPos+bestOp.length()).trim();
            if(left.isEmpty()||right.isEmpty()){
                // унарный? пропуск
            } else {
                compileExpr(left, code);
                compileExpr(right, code);
                switch(bestOp){
                    case "+": code.add(new Bytecode.Instr(Bytecode.Op.ADD)); break;
                    case "-": code.add(new Bytecode.Instr(Bytecode.Op.SUB)); break;
                    case "*": code.add(new Bytecode.Instr(Bytecode.Op.MUL)); break;
                    case "/": code.add(new Bytecode.Instr(Bytecode.Op.DIV)); break;
                    case "%": code.add(new Bytecode.Instr(Bytecode.Op.MOD)); break;
                    case "^":
                    case "POW": code.add(new Bytecode.Instr(Bytecode.Op.POW)); break;
                    case "==": code.add(new Bytecode.Instr(Bytecode.Op.EQ)); break;
                    case "!=": code.add(new Bytecode.Instr(Bytecode.Op.NE)); break;
                    case "<": code.add(new Bytecode.Instr(Bytecode.Op.LT)); break;
                    case ">": code.add(new Bytecode.Instr(Bytecode.Op.GT)); break;
                    case "<=": code.add(new Bytecode.Instr(Bytecode.Op.LE)); break;
                    case ">=": code.add(new Bytecode.Instr(Bytecode.Op.GE)); break;
                    case "&&": code.add(new Bytecode.Instr(Bytecode.Op.AND)); break;
                    case "||": code.add(new Bytecode.Instr(Bytecode.Op.OR)); break;
                }
                return;
            }
        }
        // методы объекта txt.lower() / box.get() / list.add("x") / arr[0] уже выше
        if(expr.matches(".+\\.\\w+\\(.*\\)")){
            Matcher dmm = Pattern.compile("(.+)\\.([a-zA-Z_][a-zA-Z0-9_]*)\\((.*)\\)", Pattern.DOTALL).matcher(expr);
            if(dmm.matches()){
                String obj = dmm.group(1).trim();
                String method = dmm.group(2).trim();
                String args = dmm.group(3).trim();
                compileExpr(obj, code);
                if(!args.isEmpty()){
                    List<String> parts=splitArgsRespectingStringsAndBrackets(args);
                    for(String p: parts) compileExpr(p.trim(), code);
                }
                code.add(new Bytecode.Instr(Bytecode.Op.CALL, method));
                return;
            }
        }
        // вызовы func(...)
        if(expr.matches("\\w+\\s*\\(.*\\)") || expr.matches("\\w+<.*>\\s*\\(.*\\)")){
            Matcher fm = Pattern.compile("(\\w+)(?:<.*>)?\\s*\\((.*)\\)", Pattern.DOTALL).matcher(expr);
            if(fm.matches()){
                String fn=fm.group(1); String args=fm.group(2);
                List<String> parts=splitArgsRespectingStringsAndBrackets(args);
                for(String p: parts) if(!p.trim().isEmpty()) compileExpr(p.trim(), code);
                switch(fn){
                    case "sum": for(int i=1;i<parts.size();i++) if(!parts.get(i).trim().isEmpty()) code.add(new Bytecode.Instr(Bytecode.Op.ADD)); break;
                    case "sub": code.add(new Bytecode.Instr(Bytecode.Op.SUB)); break;
                    case "mult": code.add(new Bytecode.Instr(Bytecode.Op.MUL)); break;
                    case "div": code.add(new Bytecode.Instr(Bytecode.Op.DIV)); break;
                    default: code.add(new Bytecode.Instr(Bytecode.Op.CALL, fn)); break;
                }
                return;
            }
        }
        // Map literal {"name": "Иван", "age": 25}
        if(expr.startsWith("{") && expr.endsWith("}") && expr.contains(":")){
            String inner=expr.substring(1,expr.length()-1).trim();
            code.add(new Bytecode.Instr(Bytecode.Op.CALL, "Map"));
            String storeVar="__mapTmp"+code.size();
            code.add(new Bytecode.Instr(Bytecode.Op.STORE, storeVar));
            List<String> pairs=splitArgsRespectingStringsAndBrackets(inner);
            for(String pair: pairs){
                int colon=pair.indexOf(":");
                if(colon==-1) continue;
                String k=pair.substring(0,colon).trim();
                String v=pair.substring(colon+1).trim();
                k=stripQuotes(k);
                code.add(new Bytecode.Instr(Bytecode.Op.LOAD, storeVar));
                code.add(new Bytecode.Instr(Bytecode.Op.PUSH, k));
                compileExpr(v, code);
                code.add(new Bytecode.Instr(Bytecode.Op.CALL, "put"));
                code.add(new Bytecode.Instr(Bytecode.Op.POP));
            }
            code.add(new Bytecode.Instr(Bytecode.Op.LOAD, storeVar));
            return;
        }
        // Box<String>() -> Box
        if(expr.matches("Box<.*>\\(\\)")||expr.equals("Box()")){
            code.add(new Bytecode.Instr(Bytecode.Op.CALL, "Box"));
            return;
        }
        // переменная или comp.member
        // обработка this.x -> LOAD x ?
        if(expr.contains(".")){
            // this.x -> x
            if(expr.startsWith("this.")) expr=expr.substring(5);
        }
        code.add(new Bytecode.Instr(Bytecode.Op.LOAD, expr));
    }
}
