package misha;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Runtime для языка Misha (.mih/.mixa) - покрывает раздел 10-11-17 спеки
 * Все встроенные функции из спеки 10 мапятся сюда: sum, sub, pow, strlen, len, randint и т.д.
 */
public class Runtime {
    // 10. Математические
    public static Number sum(Number... args){ double s=0; for(Number n:args) s+=n.doubleValue(); return s; }
    public static Number sum(int a,int b){return a+b;} public static Number sum(double a,double b){return a+b;}
    public static Number sub(Number a,Number b){return a.doubleValue()-b.doubleValue();}
    public static Number mult(Number a,Number b){return a.doubleValue()*b.doubleValue();}
    public static Number div(Number a,Number b){return a.doubleValue()/b.doubleValue();}
    public static Number mod(Number a,Number b){return a.doubleValue()%b.doubleValue();}
    public static Number pow(Number a,Number b){return Math.pow(a.doubleValue(), b.doubleValue());}
    public static Number sqrt(Number a){return Math.sqrt(a.doubleValue());}
    public static Number abs(Number a){return Math.abs(a.doubleValue());}
    public static Number min(Number a,Number b){return Math.min(a.doubleValue(), b.doubleValue());}
    public static Number max(Number a,Number b){return Math.max(a.doubleValue(), b.doubleValue());}
    public static Number avg(Number... args){ double s=0; for(Number n:args) s+=n.doubleValue(); return s/args.length; }
    public static Number inc(Number a){return a.doubleValue()+1;}
    public static Number dec(Number a){return a.doubleValue()-1;}
    // Строковые 10
    public static int strlen(String s){return s.length();}
    public static String concat(String a,String b){return a+b;}
    public static String substr(String s,int a,int b){return s.substring(a,b);}
    public static String replace(String s,String a,String b){return s.replace(a,b);}
    public static boolean contains(String s,String sub){return s.contains(sub);}
    public static int index(String s,String sub){return s.indexOf(sub);}
    public static String[] split(String s,String d){return s.split(d);}
    public static String upper(String s){return s.toUpperCase();}
    public static String lower(String s){return s.toLowerCase();}
    public static String trim(String s){return s.trim();}
    public static String repeat(String s,int n){return s.repeat(n);}
    // Массивы 10
    public static int len(Object o){ if(o instanceof Collection c) return c.size(); if(o instanceof Object[] a) return a.length; if(o instanceof String s) return s.length(); return 0; }
    public static Object first(List l){return l.isEmpty()?null:l.get(0);}
    public static Object last(List l){return l.isEmpty()?null:l.get(l.size()-1);}
    public static List append(List l,Object o){ List n=new ArrayList(l); n.add(o); return n; }
    public static List prepend(Object o,List l){ List n=new ArrayList(); n.add(o); n.addAll(l); return n; }
    public static List remove(List l,Object o){ List n=new ArrayList(l); n.remove(o); return n; }
    public static List reverse(List l){ List n=new ArrayList(l); Collections.reverse(n); return n; }
    public static List sort(List l){ List n=new ArrayList(l); Collections.sort(n, (Comparator)Comparator.naturalOrder()); return n; }
    public static List unique(List l){ return new ArrayList<>(new LinkedHashSet(l)); }
    public static boolean isEmpty(Collection c){return c==null||c.isEmpty();}
    // Логические 10
    public static boolean and(boolean a,boolean b){return a&&b;}
    public static boolean or(boolean a,boolean b){return a||b;}
    public static boolean not(boolean a){return !a;}
    public static boolean xor(boolean a,boolean b){return a^b;}
    // Округление
    public static long round(double d){return Math.round(d);}
    public static long floor(double d){return (long)Math.floor(d);}
    public static long ceil(double d){return (long)Math.ceil(d);}
    // Проверки типов
    public static boolean isInt(Object o){return o instanceof Integer;}
    public static boolean isString(Object o){return o instanceof String;}
    public static boolean isArray(Object o){return o instanceof List || o instanceof Object[];}
    public static boolean isNull(Object o){return o==null;}
    public static String typeOf(Object o){return o==null?"null":o.getClass().getSimpleName();}
    // Конвертации
    public static int toInt(String s){return Integer.parseInt(s);}
    public static String toString(Object o){return String.valueOf(o);}
    public static double toFloat(String s){return Double.parseDouble(s);}
    public static Object[] toArray(String s){return s.split(",");}
    // Случайные 10
    static final Random RND = new Random();
    public static int randint(int max){return RND.nextInt(max+1);}
    public static int randint(int a,int b){return a+RND.nextInt(b-a+1);}
    public static double randfloat(){return RND.nextDouble();}
    public static double randfloat(double max){return RND.nextDouble()*max;}
    public static double randfloat(double a,double b){return a+RND.nextDouble()*(b-a);}
    public static Object choice(List l){return l.get(RND.nextInt(l.size()));}
    public static String choice(String s){return String.valueOf(s.charAt(RND.nextInt(s.length())));}
    public static boolean randbool(){return RND.nextBoolean();}
    public static String randchar(){return String.valueOf((char)('a'+RND.nextInt(26)));}
    public static String randstring(int n){ StringBuilder sb=new StringBuilder(); for(int i=0;i<n;i++) sb.append((char)('a'+RND.nextInt(26))); return sb.toString();}
    public static int randrange(int n){return RND.nextInt(n);}
    public static int randrange(int a,int b){return a+RND.nextInt(b-a);}
    // Прочее
    public static void println(Object o){System.out.println(o);}
    public static void sleep(long ms){try{Thread.sleep(ms);}catch(Exception ignored){}}
    public static String now(){return new Date().toString();}
    public static String date(){return java.time.LocalDate.now().toString();}
    public static String time(){return java.time.LocalTime.now().toString();}
    // 17 память заглушки
    public static Object alloc(int size){return new byte[size];}
    public static Object allocArray(int n){return new Object[n];}
    public static void free(Object o){}
    public static void freeArray(Object o){}
    public static Object realloc(Object o,int n){return new byte[n];}
    public static void gc(){System.gc();}
    public static String meminfo(){return "meminfo: max="+java.lang.Runtime.getRuntime().maxMemory();}
    public static long memused(){return java.lang.Runtime.getRuntime().totalMemory()-java.lang.Runtime.getRuntime().freeMemory();}
    public static long memfree(){return java.lang.Runtime.getRuntime().freeMemory();}
    public static long memtotal(){return java.lang.Runtime.getRuntime().totalMemory();}
    // File I/O + Http
    public static String read(String path){ try{return java.nio.file.Files.readString(java.nio.file.Path.of(path));}catch(Exception e){return "";} }
    public static void write(String path, String content){ try{ java.nio.file.Files.writeString(java.nio.file.Path.of(path), content); }catch(Exception ignored){} }
    public static boolean exists(String path){ return java.nio.file.Files.exists(java.nio.file.Path.of(path)); }
    public static void appendFile(String path, String content){ try{ java.nio.file.Files.writeString(java.nio.file.Path.of(path), content, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); }catch(Exception ignored){} }
    public static boolean delete(String path){ try{return java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(path));}catch(Exception e){return false;}}
    public static java.util.List<String> listFiles(String dir){ try{ return java.nio.file.Files.list(java.nio.file.Path.of(dir)).map(p->p.getFileName().toString()).collect(java.util.stream.Collectors.toList()); }catch(Exception e){return java.util.List.of();}}
    public static void mkdir(String dir){ try{ java.nio.file.Files.createDirectories(java.nio.file.Path.of(dir)); }catch(Exception ignored){} }
    public static String fetch(String url){ return httpGet(url); }
    public static String httpGet(String url){ try{ var c=java.net.http.HttpClient.newHttpClient(); var r=java.net.http.HttpRequest.newBuilder().uri(java.net.URI.create(url)).GET().build(); return c.send(r, java.net.http.HttpResponse.BodyHandlers.ofString()).body(); }catch(Exception e){return "ERR:"+e.getMessage();}}
    public static String httpPost(String url, String body){ try{ var c=java.net.http.HttpClient.newHttpClient(); var r=java.net.http.HttpRequest.newBuilder().uri(java.net.URI.create(url)).POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).header("Content-Type","application/json").build(); return c.send(r, java.net.http.HttpResponse.BodyHandlers.ofString()).body(); }catch(Exception e){return "ERR:"+e.getMessage();}}
}
