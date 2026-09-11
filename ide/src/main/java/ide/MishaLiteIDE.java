package ide;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.*;
import java.nio.file.*;
import compiler.BootstrapCompiler;
import mvm.Bytecode;
import mvm.MVM;

/**
 * Misha Lite IDE - для слабых ПК (Phenom II X4 / 4GB DDR3 / RX550)
 * ~120 строк, без подсветки на каждый ввод, -Xmx256m
 * .mix/.mih/.mixa -> MVM
 */
public class MishaLiteIDE extends JFrame {
    private JTextArea editor;
    private JTextArea out;
    private File cur;

    public MishaLiteIDE(){
        super("Misha Lite IDE .mix - 2GB Friendly");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(900, 600);
        setLocationRelativeTo(null);

        // Меню минимальное
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        top.setBackground(new Color(45,45,45));
        JButton bOpen = btn("Открыть");
        JButton bSave = btn("Сохранить");
        JButton bSaveAs = btn("Сохранить как .mix");
        JButton bCheck = btn("✓ Проверить");
        bCheck.setBackground(new Color(80,80,120)); bCheck.setForeground(Color.WHITE);
        JButton bRun = btn("▶ Запустить F5");
        bRun.setBackground(new Color(0,120,0)); bRun.setForeground(Color.WHITE);
        top.add(bOpen); top.add(bSave); top.add(bSaveAs); top.add(bCheck); top.add(Box.createHorizontalStrut(10)); top.add(bRun);
        add(top, BorderLayout.NORTH);

        editor = new JTextArea();
        editor.setFont(new Font("Consolas", Font.PLAIN, 14));
        editor.setBackground(new Color(30,30,30));
        editor.setForeground(new Color(220,220,220));
        editor.setCaretColor(Color.WHITE);
        editor.setTabSize(4); // пробел не огромный (было 8)
        editor.setText("// Lite .mix - Phenom II X4 / 4GB DDR3 / RX550\n// F5 = Запустить, Ctrl+S = Сохранить\nvar name = \"Misha\"\nprint(\"Hi {name}!\")\nfor (var i=0; i<3; i++) {\n    print(\"i={i}\")\n}\n");
        // авто-таб при Enter
        editor.addKeyListener(new java.awt.event.KeyAdapter(){
            public void keyPressed(java.awt.event.KeyEvent e){
                if(e.getKeyCode()==java.awt.event.KeyEvent.VK_ENTER){
                    try{
                        int caret = editor.getCaretPosition();
                        int line = editor.getLineOfOffset(caret);
                        int start = editor.getLineStartOffset(line);
                        int end = editor.getLineEndOffset(line);
                        String prev = editor.getText(start, end-start);
                        String indent = "";
                        for(char c: prev.toCharArray()){
                            if(c==' '||c=='\t') indent+=c; else break;
                        }
                        if(prev.trim().endsWith("{")) indent+="    ";
                        // отложенно вставить отступ после перевода строки
                        String add = indent;
                        SwingUtilities.invokeLater(()-> {
                            try{ editor.getDocument().insertString(editor.getCaretPosition(), add, null); }catch(Exception ignored){}
                        });
                    }catch(Exception ignored){}
                }
                // Tab = 4 пробела вместо \t (меньше)
                if(e.getKeyCode()==java.awt.event.KeyEvent.VK_TAB){
                    e.consume();
                    try{ editor.getDocument().insertString(editor.getCaretPosition(), "    ", null); }catch(Exception ignored){}
                }
            }
        });
        JScrollPane spE = new JScrollPane(editor);
        spE.setBorder(BorderFactory.createTitledBorder(BorderFactory.createLineBorder(Color.GRAY), ".mix / .mih / .mixa"));

        out = new JTextArea(6,0);
        out.setEditable(false);
        out.setFont(new Font("Consolas", Font.PLAIN, 12));
        out.setBackground(new Color(15,15,15));
        out.setForeground(new Color(180,255,180));
        JScrollPane spO = new JScrollPane(out);
        spO.setBorder(BorderFactory.createTitledBorder("Вывод MVM (256MB)"));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, spE, spO);
        split.setDividerLocation(380);
        split.setResizeWeight(0.85);
        add(split, BorderLayout.CENTER);

        JLabel st = new JLabel(" Готов | C:\\Users\\Mixa\\Desktop\\misha-lang | Phenom II X4 4x3.4GHz | 4GB DDR3 (свободно ~0.5GB) | RX550 4GB 1920x1080 | LTSC x64 | Heap 256MB");
        st.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        add(st, BorderLayout.SOUTH);

        JFileChooser fc = new JFileChooser("C:\\Users\\Mixa\\Desktop\\misha-lang\\examples");
        fc.setFileFilter(new FileNameExtensionFilter("Misha .mix/.mih/.mixa", "mix","mih","mixa"));

        bOpen.addActionListener(e->{
            if(fc.showOpenDialog(this)==JFileChooser.APPROVE_OPTION){
                cur=fc.getSelectedFile();
                try{ editor.setText(Files.readString(cur.toPath())); out.append("[Открыт] "+cur.getName()+"\n"); checkErrors(); }catch(Exception ex){ out.append("[ERR] "+ex+"\n");}
            }
        });
        bSave.addActionListener(e-> doSave(false, fc));
        bSaveAs.addActionListener(e-> doSave(true, fc));
        bRun.addActionListener(e-> doRun());
        bCheck.addActionListener(e-> checkErrors());

        // хоткеи
        editor.getInputMap().put(KeyStroke.getKeyStroke("ctrl S"), "save");
        editor.getActionMap().put("save", new AbstractAction(){ public void actionPerformed(java.awt.event.ActionEvent e){ doSave(false, fc); }});
        editor.getInputMap().put(KeyStroke.getKeyStroke("F5"), "run");
        editor.getActionMap().put("run", new AbstractAction(){ public void actionPerformed(java.awt.event.ActionEvent e){ doRun(); }});
    }
    private JButton btn(String t){ JButton b=new JButton(t); b.setFocusPainted(false); b.setFont(new Font("Segoe UI", Font.BOLD, 12)); return b; }
    private void doSave(boolean as, JFileChooser fc){
        if(cur==null || as){
            if(fc.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION) return;
            cur=fc.getSelectedFile();
            if(!cur.getName().contains(".")) cur=new File(cur.getAbsolutePath()+".mix");
        }
        try{ Files.writeString(cur.toPath(), editor.getText()); out.append("[Сохранен] "+cur.getName()+"\n"); }catch(Exception ex){ out.append("[ERR] "+ex+"\n");}
    }
    private String checkErrors(){
        String src = editor.getText();
        StringBuilder err = new StringBuilder();
        int braces=0, parens=0; boolean inStr=false; char strCh=0;
        String[] lines = src.split("\n");
        for(int i=0;i<lines.length;i++){
            String l = lines[i];
            String t = l.trim();
            if(t.isEmpty()||t.startsWith("//")||t.startsWith("#")) continue;
            // кавычки
            for(int j=0;j<l.length();j++){
                char c=l.charAt(j);
                if(!inStr && (c=='"'||c=='\'')){ inStr=true; strCh=c; }
                else if(inStr && c==strCh && (j==0||l.charAt(j-1)!='\\')) inStr=false;
                else if(!inStr){
                    if(c=='{') braces++; else if(c=='}') braces--;
                    else if(c=='(') parens++; else if(c==')') parens--;
                }
            }
            // pub stat testprint без () и без noret
            if(t.matches(".*\\bpub\\s+stat\\s+\\w+\\s*\\{.*") && !t.contains("noret") && !t.contains("()")){
                err.append("Строка ").append(i+1).append(": pub stat testprint -> нужно noret и () : pub stat noret testprint() {  [").append(t).append("]\n");
            }
            // var без =
            if(t.matches("var\\s+\\w+\\s*$")) err.append("Строка ").append(i+1).append(": var без = и значения\n");
            // print без )
            if(t.contains("print(") && !t.contains(")")) err.append("Строка ").append(i+1).append(": нет закрывающей )\n");
            // cls vs class
            if(t.matches(".*\\bpub\\s+class\\b.*")) err.append("Строка ").append(i+1).append(": по спеке cls а не class (pub cls)\n");
        }
        if(inStr) err.append("Нет закрывающей кавычки\n");
        if(braces!=0) err.append("Дисбаланс {} : ").append(braces).append(" (лишних { если >0, } если <0)\n");
        if(parens!=0) err.append("Дисбаланс () : ").append(parens).append("\n");
        String res = err.toString();
        if(!res.isEmpty()){
            out.append("[CHECK] Ошибки:\n"+res);
        } else {
            out.append("[CHECK] OK - ошибок не найдено\n");
        }
        return res;
    }

    private void doRun(){
        String err = checkErrors();
        if(!err.isEmpty() && err.contains("Дисбаланс")){ out.append("[RUN] есть критичные ошибки, но пробую запустить...\n"); }
        String src = editor.getText();
        if(src.length()>50000){ out.append("[ERR] Файл слишком большой для 2GB, разбей\n"); return; }
        out.append("\n[COMPILE] mix -> mvm ("+src.length()+" chars)...\n");
        long t0=System.currentTimeMillis();
        try{
            var code = BootstrapCompiler.compileToMvm(src);
            out.append("[OK] "+code.size()+" instr за "+(System.currentTimeMillis()-t0)+"ms\n");
            out.append("[RUN] MVM в фоне (окно не блокирует IDE)...\n");
            new Thread(() -> {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
                PrintStream old=System.out;
                PrintStream ps=null;
                try{
                    ps=new PrintStream(baos, true, java.nio.charset.StandardCharsets.UTF_8);
                    System.setOut(ps);
                    new MVM(code).run();
                } catch(Exception ex){
                    StringWriter sw=new StringWriter(); ex.printStackTrace(new PrintWriter(sw));
                    javax.swing.SwingUtilities.invokeLater(() -> out.append("[ERR MVM thread] "+sw.toString().substring(0,Math.min(1500,sw.toString().length()))+"\n"));
                } finally {
                    System.setOut(old);
                    if(ps!=null) ps.close();
                    String r;
                    try{ r=baos.toString(java.nio.charset.StandardCharsets.UTF_8); }catch(Exception e){ r="[enc err]"; }
                    if(r.length()>3000) r=r.substring(0,3000)+"\n... обрезано для 2GB\n";
                    String fr=r;
                    javax.swing.SwingUtilities.invokeLater(() -> {
                        out.append("--- MVM ---\n"+fr+"\n// halt OK (фон)\n");
                        out.setCaretPosition(out.getDocument().getLength());
                        Runtime rt=Runtime.getRuntime();
                        long free=rt.freeMemory()/1024/1024, total=rt.totalMemory()/1024/1024;
                        out.append(String.format("[RAM] free %dMB / total %dMB | Закрой браузер для +500MB\n", free, total));
                    });
                }
            }, "Misha-MVM").start();
        }catch(Exception e){
            out.append("[ERR] "+e.getMessage()+"\n");
            StringWriter sw=new StringWriter(); e.printStackTrace(new PrintWriter(sw));
            String s=sw.toString(); out.append(s.substring(0, Math.min(1500,s.length()))+"\n");
        }
    }
    public static void main(String[] args){
        // экономный LookAndFeel
        try{ UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }catch(Exception ignored){}
        // совет для Phenom - отключить анимации Windows перед запуском
        SwingUtilities.invokeLater(()-> new MishaLiteIDE().setVisible(true));
    }
}
