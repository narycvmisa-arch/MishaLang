package ide;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import compiler.BootstrapCompiler;
import mvm.Bytecode;
import mvm.MVM;

/**
 * Misha IDE - простая IDE для .mix / .mih / .mixa (MVM)
 * Запуск: javac -cp mvm/target:ide/src/main/java ide/src/main/java/ide/MishaIDE.java -d ide/target
 *         java -cp mvm/target:ide/target:runtime/src/main/java ide.MishaIDE
 */
public class MishaIDE extends JFrame {
    private JTextPane editor;
    private JTextArea console;
    private JFileChooser chooser;
    private File currentFile;
    private JLabel status;
    private final String[] KEYWORDS = {"pub","priv","prot","stat","const","abstr","sync","vol","trans","cls","ext","impl","interf","noret","ret","var","if","elif","else","for","while","do","try","catch","finally","enum","package","import","print","println"};
    private final String[] TYPES = {"str","flt","dbl","chr","bool","lng","int","String","int","float","double","char","boolean","long"};
    private final String[] ANNOT = {"#over","#dep","#test","#getter","#setter","#data","#singleton","#fast","#XOR","#AES","#BASE64","#part","#mosn","#O3","#inline"};

    public MishaIDE() {
        super("Misha IDE - MVM (.mix/.mih/.mixa) v1");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1100, 750);
        setLocationRelativeTo(null);
        initUI();
    }

    private void initUI() {
        // Toolbar
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        JButton bOpen = new JButton("Открыть");
        JButton bSave = new JButton("Сохранить");
        JButton bRun = new JButton("► Запустить (MVM)");
        JButton bCompileJava = new JButton("→ Java");
        JButton bNew = new JButton("Новый .mix");
        bar.add(bNew); bar.addSeparator(); bar.add(bOpen); bar.add(bSave); bar.addSeparator(); bar.add(bRun); bar.add(bCompileJava);
        bOpen.addActionListener(e-> open());
        bSave.addActionListener(e-> save());
        bRun.addActionListener(e-> runMvm());
        bCompileJava.addActionListener(e-> compileJava());
        bNew.addActionListener(e-> newFile());
        add(bar, BorderLayout.NORTH);

        // Editor
        editor = new JTextPane();
        editor.setFont(new Font("Consolas", Font.PLAIN, 15));
        editor.setText(loadExample());
        JScrollPane spEditor = new JScrollPane(editor);
        spEditor.setBorder(BorderFactory.createTitledBorder(".mix / .mih / .mixa"));

        // Highlight on key typed
        editor.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){
            public void insertUpdate(javax.swing.event.DocumentEvent e){ SwingUtilities.invokeLater(()-> highlight()); }
            public void removeUpdate(javax.swing.event.DocumentEvent e){ SwingUtilities.invokeLater(()-> highlight()); }
            public void changedUpdate(javax.swing.event.DocumentEvent e){}
        });

        // Console
        console = new JTextArea(8, 0);
        console.setEditable(false);
        console.setFont(new Font("Consolas", Font.PLAIN, 13));
        console.setBackground(new Color(15,15,15));
        console.setForeground(new Color(200,255,200));
        JScrollPane spConsole = new JScrollPane(console);
        spConsole.setBorder(BorderFactory.createTitledBorder("Консоль MVM / Компилятор"));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, spEditor, spConsole);
        split.setDividerLocation(480);
        add(split, BorderLayout.CENTER);

        // Status
        status = new JLabel(" Готов | C:\\Users\\Mixa\\Desktop\\misha-lang | UTF-8 | MVM v1");
        add(status, BorderLayout.SOUTH);

        // File chooser
        chooser = new JFileChooser("C:\\Users\\Mixa\\Desktop\\misha-lang\\examples");
        chooser.setFileFilter(new FileNameExtensionFilter("Misha (.mix, .mih, .mixa, .mvm)", "mix","mih","mixa","mvm"));

        // Hotkeys
        editor.getInputMap().put(KeyStroke.getKeyStroke("ctrl S"), "save");
        editor.getActionMap().put("save", new AbstractAction(){ public void actionPerformed(ActionEvent e){ save(); }});
        editor.getInputMap().put(KeyStroke.getKeyStroke("F5"), "run");
        editor.getActionMap().put("run", new AbstractAction(){ public void actionPerformed(ActionEvent e){ runMvm(); }});

        highlight();
    }

    private String loadExample(){
        try{ return Files.readString(Path.of("C:\\Users\\Mixa\\Desktop\\misha-lang\\examples\\hello.mixa")); }catch(Exception e){
            return "// new .mix - Misha language (20 разделов)\n// pub cls Demo { pub noret run() { print(\"hi\") } }\npackage demo\n\npub cls Hello {\n    pub noret main() {\n        var name = \"Misha\"\n        print(\"Привет {name}!\")\n        for (var i=0; i<3; i++) { print(\"i={i}\") }\n    }\n}\n";
        }
    }

    private void highlight(){
        StyledDocument doc = editor.getStyledDocument();
        String text;
        try{ text = doc.getText(0, doc.getLength()); }catch(Exception e){return;}
        // сброс
        Style def = StyleContext.getDefaultStyleContext().getStyle(StyleContext.DEFAULT_STYLE);
        doc.setCharacterAttributes(0, text.length(), doc.getStyle("default")!=null? doc.getStyle("default"): def, true);
        // стили
        Style kw = doc.addStyle("kw", null); StyleConstants.setForeground(kw, new Color(86,156,214)); StyleConstants.setBold(kw,true);
        Style tp = doc.addStyle("tp", null); StyleConstants.setForeground(tp, new Color(78,201,176));
        Style ann = doc.addStyle("ann", null); StyleConstants.setForeground(ann, new Color(255,185,0));
        Style str = doc.addStyle("str", null); StyleConstants.setForeground(str, new Color(206,145,120));
        Style comm = doc.addStyle("comm", null); StyleConstants.setForeground(comm, new Color(106,153,85)); StyleConstants.setItalic(comm,true);
        // комментарии
        apply(doc, text, "//.*", comm);
        // строки
        apply(doc, text, "\"(\\\\.|[^\"])*\"", str);
        // аннотации
        for(String a: ANNOT) apply(doc, text, Pattern.quote(a)+"\\b", ann);
        // ключевые
        for(String k: KEYWORDS) apply(doc, text, "\\b"+Pattern.quote(k)+"\\b", kw);
        for(String t: TYPES) apply(doc, text, "\\b"+Pattern.quote(t)+"\\b", tp);
    }
    private void apply(StyledDocument doc, String text, String regex, Style style){
        try{
            Matcher m = Pattern.compile(regex).matcher(text);
            while(m.find()) doc.setCharacterAttributes(m.start(), m.end()-m.start(), style, false);
        }catch(Exception ignored){}
    }

    private void open(){
        if(chooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION){
            currentFile = chooser.getSelectedFile();
            try{
                String t = Files.readString(currentFile.toPath());
                editor.setText(t);
                status.setText(" Открыт: "+currentFile.getAbsolutePath());
                console.append("[IDE] Открыт "+currentFile.getName()+"\n");
            }catch(Exception e){ console.append("[ERR] "+e.getMessage()+"\n"); }
        }
    }
    private void save(){
        if(currentFile==null){
            if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION) return;
            currentFile = chooser.getSelectedFile();
            if(!currentFile.getName().contains(".")) currentFile = new File(currentFile.getAbsolutePath()+".mix");
        }
        try{
            Files.writeString(currentFile.toPath(), editor.getText());
            status.setText(" Сохранен: "+currentFile.getAbsolutePath());
            console.append("[IDE] Сохранен "+currentFile.getName()+"\n");
        }catch(Exception e){ console.append("[ERR] "+e.getMessage()+"\n"); }
    }
    private void newFile(){
        currentFile=null;
        editor.setText("// new .mix - Misha v1\npackage demo\n\npub cls App {\n    pub noret main() {\n        print(\"Hello Misha .mix\")\n    }\n}\n");
        status.setText(" Новый файл .mix");
    }

    private void runMvm(){
        String src = editor.getText();
        if(currentFile!=null) try{ Files.writeString(currentFile.toPath(), src); }catch(Exception ignored){}
        console.append("\n[COMPILE] .mix -> .mvm ...\n");
        try{
            var code = BootstrapCompiler.compileToMvm(src);
            String dis = Bytecode.disasm(code);
            console.append(dis+"\n");
            console.append("[RUN] MVM в фоне (IDE не виснет)...\n");
            status.setText(" Запущено MVM фон: "+code.size()+" instr");
            final var fcode = code;
            final String fdis = dis;
            new Thread(() -> {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                PrintStream old = System.out;
                PrintStream ps=null;
                try{
                    ps=new PrintStream(baos, true, "UTF-8");
                    System.setOut(ps);
                    new MVM(fcode).run();
                } catch(Exception ex){
                    StringWriter sw=new StringWriter(); ex.printStackTrace(new PrintWriter(sw));
                    SwingUtilities.invokeLater(() -> console.append("[ERR MVM thread] "+sw.toString()+"\n"));
                } finally {
                    System.setOut(old);
                    if(ps!=null) ps.close();
                    String out;
                    try{ out=baos.toString("UTF-8"); }catch(Exception e){ out="[enc err]"; }
                    final String fout = out;
                    SwingUtilities.invokeLater(() -> {
                        console.append("[RUN MVM] вывод (фон):\n" + fout + "\n// halt - OK ("+fcode.size()+" instr)\n");
                        console.setCaretPosition(console.getDocument().getLength());
                        status.setText(" MVM фон завершён: "+fcode.size()+" instr OK");
                    });
                }
            }, "Misha-MVM").start();
        }catch(Exception e){
            console.append("[ERR MVM] "+e.getMessage()+"\n");
            StringWriter sw=new StringWriter(); e.printStackTrace(new PrintWriter(sw)); console.append(sw.toString()+"\n");
            status.setText(" Ошибка MVM");
        }
    }

    private void compileJava(){
        String src = editor.getText();
        console.append("\n[TRANSPILE] .mix -> .java ...\n");
        try{
            String javaCode = BootstrapCompiler.transpileToJava(src);
            console.append(javaCode.substring(0, Math.min(2000, javaCode.length())) + "\n...\n[OK] Java сгенерирован ("+javaCode.length()+" chars)\n");
            // пробуем скомпилировать в памяти через javac API если есть
            status.setText(" Транспилирован в Java");
        }catch(Exception e){
            console.append("[ERR Java] "+e.getMessage()+"\n");
        }
    }

    public static void main(String[] args){
        try{ UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }catch(Exception ignored){}
        SwingUtilities.invokeLater(()-> new MishaIDE().setVisible(true));
    }
}
