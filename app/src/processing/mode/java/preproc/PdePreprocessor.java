/* -*- mode: java; c-basic-offset: 2; indent-tabs-mode: nil -*- */
package processing.mode.java.preproc;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.TokenStreamRewriter;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import processing.app.Preferences;
import processing.app.SketchException;
import processing.core.PApplet;

public class PdePreprocessor {
  
  public static enum Mode {
    STATIC, ACTIVE, JAVA
  }
  
  private String sketchName;
  private int tabSize;
  
  private boolean hasMain;
  
  private final boolean isTested;
  
  public PdePreprocessor(final String sketchName) {
    this(sketchName, Preferences.getInteger("editor.tabs.size"), false);
  }
  
  public PdePreprocessor(final String sketchName, final int tabSize) {
    this(sketchName, tabSize, false);
  }
  
  public PdePreprocessor(final String sketchName, final int tabSize, boolean isTested) {
    this.sketchName = sketchName;
    this.tabSize = tabSize;
    this.isTested = isTested;
  }
  
  public PreprocessorResult write(final Writer out, String program) throws SketchException {
    return write(out, program, null);
  }
  
  public PreprocessorResult write(Writer outWriter, String inProgram,
                                  String codeFolderPackages[])
                                    throws SketchException {
    
    ArrayList<String> codeFolderImports = new ArrayList<String>();
    if (codeFolderPackages != null) {
      for (String item : codeFolderPackages) {
        codeFolderImports.add(item + ".*");
      }
    }
    
    if (Preferences.getBoolean("preproc.substitute_unicode")) {
      inProgram = substituteUnicode(inProgram);
    }
    
    while (inProgram.endsWith("\n")) {
      inProgram = inProgram.substring(0, inProgram.length() - 1);
    }
    
    CommonTokenStream tokens;
    {
      ANTLRInputStream antlrInStream = new ANTLRInputStream(inProgram);
      ProcessingLexer lexer = new ProcessingLexer(antlrInStream);
      tokens = new CommonTokenStream(lexer);
    }
    
    PdeParseTreeListener listener = new PdeParseTreeListener(tokens, sketchName);
    listener.setIsTested(isTested);
    listener.setIndent(tabSize);
    listener.setCoreImports(getCoreImports());
    listener.setDefaultImports(getDefaultImports());
    listener.setCodeFolderImports(codeFolderImports);
    
    ParseTree tree;
    {
      ProcessingParser parser = new ProcessingParser(tokens);
      parser.setBuildParseTree(true);
      tree = parser.processingSketch();
    }
    
    ParseTreeWalker treeWalker = new ParseTreeWalker();
    treeWalker.walk(listener, tree);
    
    String outputProgram = listener.getOutputProgram();
    PrintWriter outPrintWriter = new PrintWriter(outWriter);
    System.out.println(outputProgram);
    outPrintWriter.print(outputProgram);
    
    hasMain = listener.foundMain;

    return listener.getResult();
  }
  
  protected PdeParseTreeListener createListener(CommonTokenStream tokens, String sketchName) {
    return new PdeParseTreeListener(tokens, sketchName);
  }
  
  public boolean hasMain() {
    return hasMain;
  }
  
  private static String substituteUnicode(String program) {
    // check for non-ascii chars (these will be/must be in unicode format)
    char p[] = program.toCharArray();
    int unicodeCount = 0;
    for (int i = 0; i < p.length; i++) {
      if (p[i] > 127)
        unicodeCount++;
    }
    if (unicodeCount == 0)
      return program;
    // if non-ascii chars are in there, convert to unicode escapes
    // add unicodeCount * 5.. replacing each unicode char
    // with six digit uXXXX sequence (xxxx is in hex)
    // (except for nbsp chars which will be a replaced with a space)
    int index = 0;
    char p2[] = new char[p.length + unicodeCount * 5];
    for (int i = 0; i < p.length; i++) {
      if (p[i] < 128) {
        p2[index++] = p[i];
      } else if (p[i] == 160) { // unicode for non-breaking space
        p2[index++] = ' ';
      } else {
        int c = p[i];
        p2[index++] = '\\';
        p2[index++] = 'u';
        char str[] = Integer.toHexString(c).toCharArray();
        // add leading zeros, so that the length is 4
        //for (int i = 0; i < 4 - str.length; i++) p2[index++] = '0';
        for (int m = 0; m < 4 - str.length; m++)
          p2[index++] = '0';
        System.arraycopy(str, 0, p2, index, str.length);
        index += str.length;
      }
    }
    return new String(p2, 0, index);
  }
  
  public String[] getCoreImports() {
    return new String[] {
      "processing.core.*",
      "processing.data.*",
      "processing.event.*",
      "processing.opengl.*"
    };
  }

  public String[] getDefaultImports() {
    // These may change in-between (if the prefs panel adds this option)
    //String prefsLine = Preferences.get("preproc.imports");
    //return PApplet.splitTokens(prefsLine, ", ");
    return new String[] { 
      "java.util.HashMap",
      "java.util.ArrayList",
      "java.io.File",
      "java.io.BufferedReader",
      "java.io.PrintWriter",
      "java.io.InputStream",
      "java.io.OutputStream",
      "java.io.IOException"
    };
  }
  
  public static class PdeParseTreeListener extends ProcessingBaseListener {

    private final static String version = "3.0.0";
    
    private String sketchName;
    private boolean isTested;
    private TokenStreamRewriter rewriter;
    
    private Mode mode = Mode.JAVA;
    private boolean foundMain;
    
    private int lineOffset;
    
    private ArrayList<String> coreImports = new ArrayList<String>();
    private ArrayList<String> defaultImports = new ArrayList<String>();
    private ArrayList<String> codeFolderImports = new ArrayList<String>();
    private ArrayList<String> foundImports = new ArrayList<String>();

    private String indent1 = "";
    private String indent2 = "";
    private String indent3 = "";
    
    private String sketchWidth;
    private String sketchHeight;
    private String sketchRenderer;

    private boolean hasSketchWidthMethod;
    private boolean hasSketchHeightMethod;
    private boolean hasSketchRendererMethod;
    
    private boolean isSizeValid;

    PdeParseTreeListener(BufferedTokenStream tokens, String sketchName) {
      rewriter = new TokenStreamRewriter(tokens);
      this.sketchName = sketchName;
    }
    
    protected void setCodeFolderImports(List<String> codeFolderImports) {
      this.codeFolderImports.clear();
      this.codeFolderImports.addAll(codeFolderImports);
    }
    
    protected void setCoreImports(String[] coreImports) {
      setCoreImports(Arrays.asList(coreImports));
    }
    
    protected void setCoreImports(List<String> coreImports) {
      this.coreImports.clear();
      this.coreImports.addAll(coreImports);
    }
    
    protected void setDefaultImports(String[] defaultImports) {
      setDefaultImports(Arrays.asList(defaultImports));
    }
    
    protected void setDefaultImports(List<String> defaultImports) {
      this.defaultImports.clear();
      this.defaultImports.addAll(defaultImports);
    }
    
    protected void setIndent(int indent) {
      final char[] indentChars = new char[indent];
      Arrays.fill(indentChars, ' ');
      indent1 = new String(indentChars);
      indent2 = indent1 + indent1;
      indent3 = indent2 + indent1;
    }
    
    protected void setIsTested(boolean isTested) {
      this.isTested = isTested;
    }
    
    public String getOutputProgram() {
      return rewriter.getText();
    }
    
    public PreprocessorResult getResult() throws SketchException {
      return new PreprocessorResult(mode, lineOffset, sketchName, foundImports);
    }
    
    // ------------------------ writers
    
    protected void writeHeader(PrintWriter header) {       
      writePreprocessorComment(header);
      writeImports(header);
      if (mode == Mode.STATIC || mode == Mode.ACTIVE) writeClassHeader(header);
      if (mode == Mode.STATIC) writeStaticSketchHeader(header);
    }
    
    protected void writePreprocessorComment(PrintWriter header) {
      if (!isTested) {
        incLineOffset(); header.println(String.format(
          "/* autogenerated by Processing preprocessor v%s on %s */",
          version, new SimpleDateFormat("YYYY-MM-dd").format(new Date())));
      }
    }
    
    protected void writeImports(PrintWriter header) {
      writeImportList(header, coreImports);
      writeImportList(header, codeFolderImports);
      writeImportList(header, foundImports);
      writeImportList(header, defaultImports);
    }
    
    protected void writeImportList(PrintWriter header, List<String> imports) {
      writeImportList(header, imports.toArray(new String[0]));
    }
    
    protected void writeImportList(PrintWriter header, String[] imports) {
      for (String importDecl : imports) {
        incLineOffset(); header.println("import " + importDecl + ";");
      }
      if (imports.length > 0) {
        incLineOffset(); header.println();
      }
    }
    
    protected void writeClassHeader(PrintWriter header) {
      incLineOffset(); header.println("public class " + sketchName + " extends PApplet {");
      incLineOffset(); header.println();
    }
    
    protected void writeStaticSketchHeader(PrintWriter header) {
      incLineOffset(); header.println(indent1 + "public void setup() {");
    }
    
    protected void writeFooter(PrintWriter footer) {
      if (mode == Mode.STATIC) writeStaticSketchFooter(footer);
      if (mode == Mode.STATIC || mode == Mode.ACTIVE) {
        writeExtraFieldsAndMethods(footer);
        if (!foundMain) writeMain(footer); 
        writeClassFooter(footer);
      }
    }
    
    protected void writeStaticSketchFooter(PrintWriter footer) {
      footer.println(indent2 +   "noLoop();");
      footer.println(indent1 + "}");
    }
    
    protected void writeExtraFieldsAndMethods(PrintWriter classBody) {
      // can be overriden
	    /*
      if (isSizeValid) {
        if (sketchWidth != null && !hasSketchWidthMethod) {
          classBody.println();
          classBody.println(indent1 + "public int sketchWidth() { return " + sketchWidth + "; }");
        }
        if (sketchHeight != null && !hasSketchHeightMethod) {
          classBody.println();
          classBody.println(indent1 + "public int sketchHeight() { return " + sketchHeight + "; }");
        }
        if (sketchRenderer != null && !hasSketchRendererMethod) {
          classBody.println();
          classBody.println(indent1 +
              "public String sketchRenderer() { return " + sketchRenderer + "; }");
        }
      }*/
	}
    
    protected void writeMain(PrintWriter footer) {
      footer.println();
      footer.println(indent1 + "static public void main(String[] passedArgs) {");
      footer.print  (indent2 +   "String[] appletArgs = new String[] { ");

      { // assemble line with applet args
        if (Preferences.getBoolean("export.application.fullscreen")) {
          footer.print("\"" + PApplet.ARGS_FULL_SCREEN + "\", ");
  
          String bgColor = Preferences.get("run.present.bgcolor");
          footer.print("\"" + PApplet.ARGS_BGCOLOR + "=" + bgColor + "\", ");
  
          if (Preferences.getBoolean("export.application.stop")) {
            String stopColor = Preferences.get("run.present.stop.color");
            footer.print("\"" + PApplet.ARGS_STOP_COLOR + "=" + stopColor + "\", ");
          } else {
            footer.print("\"" + PApplet.ARGS_HIDE_STOP + "\", ");
          }
        }
        footer.print("\"" + sketchName + "\"");
      }
      
      footer.println(" };");
      
      footer.println(indent2 +   "if (passedArgs != null) {");
      footer.println(indent3 +     "PApplet.main(concat(appletArgs, passedArgs));");
      footer.println(indent2 +   "} else {");
      footer.println(indent3 +     "PApplet.main(appletArgs);");
      footer.println(indent2 +   "}");
      footer.println(indent1 + "}");
    }
    
    protected void writeClassFooter(PrintWriter footer) {
      footer.println("}");
    }

    // --------------------------------------------------- listener impl
    
    /**
     * Wrap the sketch code inside a class definition and
     * add all imports found to the top incl. the default ones
     */
    public void exitProcessingSketch(ProcessingParser.ProcessingSketchContext ctx) {
      { // header
        StringWriter headerSW = new StringWriter();
        PrintWriter headerPW = new PrintWriter(headerSW);
        writeHeader(headerPW);
        rewriter.insertBefore(0, headerSW.getBuffer().toString());
      }

      { // footer
        StringWriter footerSW = new StringWriter();
        PrintWriter footerPW = new PrintWriter(footerSW);
        footerPW.println();
        writeFooter(footerPW);
        rewriter.insertAfter(rewriter.getTokenStream().size(), footerSW.getBuffer().toString());
      }
    }

    protected void incLineOffset() {
      lineOffset++;
    }
    
    public void exitApiSizeFunction(ProcessingParser.ApiSizeFunctionContext ctx) {
      // this tree climbing could be avoided if grammar is 
      // adjusted to force context of size()
      
      ParserRuleContext testCtx = 
        ctx.getParent() // apiFunction
        .getParent() // expression
        .getParent() // statementExpression
        .getParent() // statement
        .getParent() // blockStatement
        .getParent(); // block or staticProcessingSketch

      boolean isSizeInSetupOrGlobal = 
        testCtx instanceof ProcessingParser.StaticProcessingSketchContext;

      if (!isSizeInSetupOrGlobal) {
        testCtx =
          testCtx.getParent() // methodBody of setup()
          .getParent(); // methodDeclaration of setup()

        String methodName = testCtx.getChild(1).getText();
        testCtx = testCtx.getParent() // memberDeclaration
          .getParent() // classBodyDeclaration
          .getParent(); // activeProcessingSketch

        isSizeInSetupOrGlobal = 
          methodName.equals("setup") && 
          testCtx instanceof ProcessingParser.ActiveProcessingSketchContext;
      }
      
      isSizeValid = false;

      if (isSizeInSetupOrGlobal) {
        isSizeValid = true;
        sketchWidth = ctx.getChild(2).getText();
        if (PApplet.parseInt(sketchWidth, -1) == -1 && !sketchWidth.equals("displayWidth")) {
          isSizeValid = false;
        }
        sketchHeight = ctx.getChild(4).getText();
        if (PApplet.parseInt(sketchHeight, -1) == -1 && !sketchHeight.equals("displayHeight")) {
          isSizeValid = false;
        }      
        if (ctx.getChildCount() > 6) {
          sketchRenderer = ctx.getChild(6).getText();
          if (!(sketchRenderer.equals("P2D") ||
                sketchRenderer.equals("P3D") ||
                sketchRenderer.equals("OPENGL") ||
                sketchRenderer.equals("JAVA2D"))) {
            isSizeValid = false;
          } 
        }
        if (isSizeValid) {
          //rewriter.insertBefore(ctx.start, "/* commented out by preprocessor: ");
          //rewriter.insertAfter(ctx.stop, " */");
        }
      }
    }
    
    /**
     * Find sketch methods
     */
    public void exitApiMethodDeclaration(ProcessingParser.ApiMethodDeclarationContext ctx) {
      String methodName = ctx.getChild(1).getText();
      if      (methodName.equals("sketchWidth"   )) hasSketchWidthMethod    = true;
      else if (methodName.equals("sketchHeight"  )) hasSketchWidthMethod    = true;
      else if (methodName.equals("sketchRenderer")) hasSketchRendererMethod = true;
    }

    /**
     * Remove import declarations, they will be included in the header.
     */
    public void exitImportDeclaration(ProcessingParser.ImportDeclarationContext ctx) {
      rewriter.delete(ctx.start, ctx.stop);
    }
    
    /**
     * Save qualified import name (with static modifier when present)
     * for inclusion in the header.
     */
    public void exitImportString(ProcessingParser.ImportStringContext ctx) {
      Interval interval = new Interval(ctx.start.getStartIndex(), ctx.stop.getStopIndex());
      String importString = ctx.start.getInputStream().getText(interval);
      foundImports.add(importString);
    }

    /**
     * Any floating point number that has not float / double suffix
     * will get a 'f' appended to make it float.
     */
    public void exitDecimalfloatingPointLiteral(ProcessingParser.DecimalfloatingPointLiteralContext ctx) {
      String cTxt = ctx.getText().toLowerCase();
      if (!cTxt.endsWith("f") && !cTxt.endsWith("d")) {
        rewriter.insertAfter(ctx.stop, "f");
      }
    }

    /**
     * Detect "static sketches"
     */
    public void exitStaticProcessingSketch(ProcessingParser.StaticProcessingSketchContext ctx) {
      mode = Mode.STATIC;
    }
    
    /**
     * Detect "active sketches"
     */
    public void exitActiveProcessingSketch(ProcessingParser.ActiveProcessingSketchContext ctx) {
      mode = Mode.ACTIVE;
    }

    /**
     * Make any method "public" that has:
     * - no other access modifier
     * - return type "void"
     * - is either in the context of the sketch class
     * - or is in the context of a class definition that extends PApplet
     */
    public void exitMethodDeclaration(ProcessingParser.MethodDeclarationContext ctx) {
      ParserRuleContext memCtx = ctx.getParent();
      ParserRuleContext clsBdyDclCtx = memCtx.getParent();
      ParserRuleContext clsBdyCtx = clsBdyDclCtx.getParent();
      ParserRuleContext clsDclCtx = clsBdyCtx.getParent();

      boolean inSketchContext = 
        clsBdyCtx instanceof ProcessingParser.StaticProcessingSketchContext ||
        clsBdyCtx instanceof ProcessingParser.ActiveProcessingSketchContext;

      boolean inPAppletContext =
        inSketchContext || (
          clsDclCtx instanceof ProcessingParser.ClassDeclarationContext &&
          clsDclCtx.getChildCount() >= 4 && 
          clsDclCtx.getChild(2).getText().equals("extends") &&
          clsDclCtx.getChild(3).getText().endsWith("PApplet"));

      boolean voidType = ctx.getChild(0).getText().equals("void");

      boolean hasModifier = clsBdyDclCtx.getChild(0) != memCtx; // not the first, so no mod before

      if (!hasModifier && inPAppletContext && voidType) {
        rewriter.insertBefore(memCtx.start, "public ");
      }

      if ((inSketchContext || inPAppletContext) && 
          hasModifier && 
          ctx.getChild(1).getText().equals("main")) {
        foundMain = true;
      }
    }

    /**
     * Change any "value converters" with the name of a primitive type
     * to their proper names:
     * int() --> parseInt()
     * float() --> parseFloat()
     * ...
     */
    public void exitFunctionWithPrimitiveTypeName(ProcessingParser.FunctionWithPrimitiveTypeNameContext ctx) {
      String fn = ctx.getChild(0).getText();
      if (!fn.equals("color")) {
        fn = "PApplet.parse" + fn.substring(0,1).toUpperCase() + fn.substring(1);
        rewriter.insertBefore(ctx.start, fn);
        rewriter.delete(ctx.start);
      }
    }

    /**
     * Fix "color type" to be "int".
     */
    public void exitColorPrimitiveType(ProcessingParser.ColorPrimitiveTypeContext ctx) {
      if (ctx.getText().equals("color")) {
        rewriter.insertBefore(ctx.start, "int");
        rewriter.delete(ctx.start, ctx.stop);
      }
    }

    /**
     * Fix hex color literal
     */
    public void exitHexColorLiteral(ProcessingParser.HexColorLiteralContext ctx) {
      rewriter.insertBefore(ctx.start, ctx.getText().toUpperCase().replace("#","0xFF"));
      rewriter.delete(ctx.start, ctx.stop);
    }
  }
}