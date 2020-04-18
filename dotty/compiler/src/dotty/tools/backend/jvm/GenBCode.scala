package dotty.tools.backend.jvm

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.ast.Trees.{PackageDef, ValDef}
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Phases.Phase

import scala.collection.mutable
import scala.collection.JavaConverters._
import dotty.tools.dotc.transform.SymUtils._
import dotty.tools.dotc.interfaces
import dotty.tools.dotc.util.SourceFile
import java.util.Optional

import dotty.tools.dotc.core._
import dotty.tools.dotc.sbt.ExtractDependencies
import Contexts._
import Symbols._
import Decorators._

import java.io.DataOutputStream

import dotty.tools.tasty.{ TastyBuffer, TastyHeaderUnpickler }

import scala.tools.asm
import scala.tools.asm.Handle
import scala.tools.asm.tree._
import tpd._
import StdNames._
import dotty.tools.io._

class GenBCode extends Phase {
  def phaseName: String = GenBCode.name

  private val superCallsMap = newMutableSymbolMap[Set[ClassSymbol]]
  def registerSuperCall(sym: Symbol, calls: ClassSymbol): Unit = {
    val old = superCallsMap.getOrElse(sym, Set.empty)
    superCallsMap.update(sym, old + calls)
  }

  private var myOutput: AbstractFile = _

  private def outputDir(implicit ctx: Context): AbstractFile = {
    if (myOutput eq null)
      myOutput = ctx.settings.outputDir.value
    myOutput
  }

  def run(implicit ctx: Context): Unit = {
    new GenBCodePipeline(new DottyBackendInterface(
      outputDir, superCallsMap.toMap)(ctx))(ctx).run(ctx.compilationUnit.tpdTree)
  }

  override def runOn(units: List[CompilationUnit])(implicit ctx: Context): List[CompilationUnit] = {
    try super.runOn(units)
    finally myOutput match {
      case jar: JarArchive =>
        if (ctx.run.suspendedUnits.nonEmpty)
          // If we close the jar the next run will not be able to write on the jar.
          // But if we do not close it we cannot use it as part of the macro classpath of the suspended files.
          ctx.error("Can not suspend and output to a jar at the same time. See suspension with -Xprint-suspension.")
        jar.close()
      case _ =>
    }
  }
}

object GenBCode {
  val name: String = "genBCode"
}

class GenBCodePipeline(val int: DottyBackendInterface)(implicit ctx: Context) extends BCodeSyncAndTry {

  private var tree: Tree = _

  private val sourceFile: SourceFile = ctx.compilationUnit.source

  /** Convert a `dotty.tools.io.AbstractFile` into a
   *  `dotty.tools.dotc.interfaces.AbstractFile`.
   */
  private def convertAbstractFile(absfile: dotty.tools.io.AbstractFile): interfaces.AbstractFile =
    new interfaces.AbstractFile {
      override def name = absfile.name
      override def path = absfile.path
      override def jfile = Optional.ofNullable(absfile.file)
    }

  final class PlainClassBuilder(cunit: CompilationUnit) extends SyncAndTryBuilder(cunit)

//  class BCodePhase() {

  private var bytecodeWriter  : BytecodeWriter   = null
  private var mirrorCodeGen   : JMirrorBuilder   = null

  /* ---------------- q1 ---------------- */

  case class Item1(arrivalPos: Int, cd: TypeDef, cunit: CompilationUnit) {
    def isPoison: Boolean = { arrivalPos == Int.MaxValue }
  }
  private val poison1 = Item1(Int.MaxValue, null, ctx.compilationUnit)
  private val q1 = new java.util.LinkedList[Item1]

  /* ---------------- q2 ---------------- */

  case class SubItem2(classNode: asm.tree.ClassNode,
                      file:      dotty.tools.io.AbstractFile)

  case class Item2(arrivalPos: Int,
                    mirror:     SubItem2,
                    plain:      SubItem2) {
    def isPoison: Boolean = { arrivalPos == Int.MaxValue }
  }

  private val poison2 = Item2(Int.MaxValue, null, null)
  private val q2 = new _root_.java.util.LinkedList[Item2]

  /* ---------------- q3 ---------------- */

  /*
    *  An item of queue-3 (the last queue before serializing to disk) contains three of these
    *  (one for each of mirror and plain classes).
    *
    *  @param jclassName  internal name of the class
    *  @param jclassBytes bytecode emitted for the class SubItem3 represents
    */
  case class SubItem3(
                        jclassName:  String,
                        jclassBytes: Array[Byte],
                        jclassFile:  dotty.tools.io.AbstractFile
                        )

  case class Item3(arrivalPos: Int,
                    mirror:     SubItem3,
                    plain:      SubItem3) {

    def isPoison: Boolean  = { arrivalPos == Int.MaxValue }
  }
  private val i3comparator = new java.util.Comparator[Item3] {
    override def compare(a: Item3, b: Item3) = {
      if (a.arrivalPos < b.arrivalPos) -1
      else if (a.arrivalPos == b.arrivalPos) 0
      else 1
    }
  }
  private val poison3 = Item3(Int.MaxValue, null, null)
  private val q3 = new java.util.PriorityQueue[Item3](1000, i3comparator)

  /*
    *  Pipeline that takes ClassDefs from queue-1, lowers them into an intermediate form, placing them on queue-2
    */
  class Worker1(needsOutFolder: Boolean) {

    private val lowerCaseNames = mutable.HashMap.empty[String, Symbol]
    private def checkForCaseConflict(javaClassName: String, classSymbol: Symbol) = {
      val lowerCaseName = javaClassName.toLowerCase
      lowerCaseNames.get(lowerCaseName) match {
        case None =>
          lowerCaseNames.put(lowerCaseName, classSymbol)
        case Some(dupClassSym) =>
          // Order is not deterministic so we enforce lexicographic order between the duplicates for error-reporting
          val (cl1, cl2) =
            if (classSymbol.effectiveName.toString < dupClassSym.effectiveName.toString) (classSymbol, dupClassSym)
            else (dupClassSym, classSymbol)
          val same = classSymbol.effectiveName.toString == dupClassSym.effectiveName.toString
          ctx.atPhase(ctx.typerPhase) {
            if (same)
              summon[Context].warning( // FIXME: This should really be an error, but then FromTasty tests fail
                s"${cl1.show} and ${cl2.showLocated} produce classes that overwrite one another", cl1.sourcePos)
            else
              summon[Context].warning(s"${cl1.show} differs only in case from ${cl2.showLocated}. " +
                "Such classes will overwrite one another on case-insensitive filesystems.", cl1.sourcePos)
          }
      }
    }

    def run(): Unit = {
      while (true) {
        val item = q1.poll
        if (item.isPoison) {
          q2 add poison2
          return
        }
        else {
          try   { /*withCurrentUnit(item.cunit)*/(visit(item)) }
          catch {
            case ex: Throwable =>
              println(s"Error while emitting ${int.sourceFileFor(item.cunit)}")
              throw ex
          }
        }
      }
    }

    /*
      *  Checks for duplicate internal names case-insensitively,
      *  builds ASM ClassNodes for mirror and plain classes;
      *  enqueues them in queue-2.
      *
      */
    def visit(item: Item1): Boolean = {
      val Item1(arrivalPos, cd, cunit) = item
      val claszSymbol = cd.symbol

      // -------------- mirror class, if needed --------------
      val mirrorC =
        if (int.symHelper(claszSymbol).isTopLevelModuleClass) {
          if (claszSymbol.companionClass == NoSymbol) {
            mirrorCodeGen.genMirrorClass(claszSymbol, cunit)
          } else {
            ctx.log(s"No mirror class for module with linked class: ${claszSymbol.showFullName}")
            null
          }
        } else null

      // -------------- "plain" class --------------
      val pcb = new PlainClassBuilder(cunit)
      pcb.genPlainClass(cd)
      val outF = if (needsOutFolder) getOutFolder(claszSymbol, pcb.thisName) else null;
      val plainC = pcb.cnode

      if (claszSymbol.isClass) // @DarkDimius is this test needed here?
        for (binary <- ctx.compilationUnit.pickled.get(claszSymbol.asClass)) {
          val store = if (mirrorC ne null) mirrorC else plainC
          val tasty =
            if (!ctx.settings.YemitTastyInClass.value) {
              val outTastyFile = getFileForClassfile(outF, store.name, ".tasty")
              val outstream = new DataOutputStream(outTastyFile.bufferedOutput)
              try outstream.write(binary)
              finally outstream.close()

              val uuid = new TastyHeaderUnpickler(binary).readHeader()
              val lo = uuid.getMostSignificantBits
              val hi = uuid.getLeastSignificantBits

              // TASTY attribute is created but only the UUID bytes are stored in it.
              // A TASTY attribute has length 16 if and only if the .tasty file exists.
              val buffer = new TastyBuffer(16)
              buffer.writeUncompressedLong(lo)
              buffer.writeUncompressedLong(hi)
              buffer.bytes
            } else {
              // Create an empty file to signal that a tasty section exist in the corresponding .class
              // This is much cheaper and simpler to check than doing classfile parsing
              getFileForClassfile(outF, store.name, ".hasTasty")
              binary
            }
          val dataAttr = createJAttribute(nme.TASTYATTR.mangledString, tasty, 0, tasty.length)
          store.visitAttribute(dataAttr)
        }


      // ----------- create files

      val classNodes = List(mirrorC, plainC)
      val classFiles = classNodes.map(cls =>
        if (outF != null && cls != null) {
          try {
            checkForCaseConflict(cls.name, claszSymbol)
            getFileForClassfile(outF, cls.name, ".class")
          } catch {
            case e: FileConflictException =>
              ctx.error(s"error writing ${cls.name}: ${e.getMessage}")
              null
          }
        } else null
      )

      // ----------- compiler and sbt's callbacks

      val (fullClassName, isLocal) = ctx.atPhase(ctx.sbtExtractDependenciesPhase) {
        (ExtractDependencies.classNameAsString(claszSymbol), claszSymbol.isLocal)
      }

      for ((cls, clsFile) <- classNodes.zip(classFiles)) {
        if (cls != null) {
          val className = cls.name.replace('/', '.')
          if (ctx.compilerCallback != null)
            ctx.compilerCallback.onClassGenerated(sourceFile, convertAbstractFile(clsFile), className)
          if (ctx.sbtCallback != null) {
            if (isLocal)
              ctx.sbtCallback.generatedLocalClass(sourceFile.jfile.orElse(null), clsFile.file)
            else {
              ctx.sbtCallback.generatedNonLocalClass(sourceFile.jfile.orElse(null), clsFile.file,
                className, fullClassName)
            }
          }
        }
      }

      // ----------- hand over to pipeline-2

      val item2 =
        Item2(arrivalPos,
          SubItem2(mirrorC, classFiles(0)),
          SubItem2(plainC, classFiles(1)))

      q2 add item2 // at the very end of this method so that no Worker2 thread starts mutating before we're done.

    } // end of method visit(Item1)

  } // end of class BCodePhase.Worker1

  /*
    *  Pipeline that takes ClassNodes from queue-2. The unit of work depends on the optimization level:
    *
    *    (a) no optimization involves:
    *          - converting the plain ClassNode to byte array and placing it on queue-3
    */
  class Worker2 {
    // lazy val localOpt = new LocalOpt(new Settings())

    private def localOptimizations(classNode: ClassNode): Unit = {
      // BackendStats.timed(BackendStats.methodOptTimer)(localOpt.methodOptimizations(classNode))
    }


    /* Return an array of all serializable lambdas in this class */
    private def collectSerializableLambdas(classNode: ClassNode): Array[Handle] = {
      val indyLambdaBodyMethods = new mutable.ArrayBuffer[Handle]
      for (m <- classNode.methods.asScala) {
        val iter = m.instructions.iterator
        while (iter.hasNext) {
          val insn = iter.next()
          insn match {
            case indy: InvokeDynamicInsnNode
                // No need to check the exact bsmArgs because we only generate
                // altMetafactory indy calls for serializable lambdas.
                if indy.bsm == BCodeBodyBuilder.lambdaMetaFactoryAltMetafactoryHandle =>
              val implMethod = indy.bsmArgs(1).asInstanceOf[Handle]
              indyLambdaBodyMethods += implMethod
            case _ =>
          }
        }
      }
      indyLambdaBodyMethods.toArray
    }

    /*
      * Add:
      *
      * private static Object $deserializeLambda$(SerializedLambda l) {
      *   try return indy[scala.runtime.LambdaDeserialize.bootstrap, targetMethodGroup$0](l)
      *   catch {
      *     case i: IllegalArgumentException =>
      *       try return indy[scala.runtime.LambdaDeserialize.bootstrap, targetMethodGroup$1](l)
      *       catch {
      *         case i: IllegalArgumentException =>
      *           ...
      *             return indy[scala.runtime.LambdaDeserialize.bootstrap, targetMethodGroup${NUM_GROUPS-1}](l)
      *       }
      *
      * We use invokedynamic here to enable caching within the deserializer without needing to
      * host a static field in the enclosing class. This allows us to add this method to interfaces
      * that define lambdas in default methods.
      *
      * SI-10232 we can't pass arbitrary number of method handles to the final varargs parameter of the bootstrap
      * method due to a limitation in the JVM. Instead, we emit a separate invokedynamic bytecode for each group of target
      * methods.
      */
    private def addLambdaDeserialize(classNode: ClassNode, implMethodsArray: Array[Handle]): Unit = {
      import asm.Opcodes._
      import BCodeBodyBuilder._
      import bTypes._
      import coreBTypes._

      val cw = classNode

      // Make sure to reference the ClassBTypes of all types that are used in the code generated
      // here (e.g. java/util/Map) are initialized. Initializing a ClassBType adds it to
      // `classBTypeFromInternalNameMap`. When writing the classfile, the asm ClassWriter computes
      // stack map frames and invokes the `getCommonSuperClass` method. This method expects all
      // ClassBTypes mentioned in the source code to exist in the map.

      val serlamObjDesc = MethodBType(jliSerializedLambdaRef :: Nil, ObjectReference).descriptor

      val mv = cw.visitMethod(ACC_PRIVATE + ACC_STATIC + ACC_SYNTHETIC, "$deserializeLambda$", serlamObjDesc, null, null)
      def emitLambdaDeserializeIndy(targetMethods: Seq[Handle]): Unit = {
        mv.visitVarInsn(ALOAD, 0)
        mv.visitInvokeDynamicInsn("lambdaDeserialize", serlamObjDesc, lambdaDeserializeBootstrapHandle, targetMethods: _*)
      }

      val targetMethodGroupLimit = 255 - 1 - 3 // JVM limit. See See MAX_MH_ARITY in CallSite.java
      val groups: Array[Array[Handle]] = implMethodsArray.grouped(targetMethodGroupLimit).toArray
      val numGroups = groups.length

      import scala.tools.asm.Label
      val initialLabels = Array.fill(numGroups - 1)(new Label())
      val terminalLabel = new Label
      def nextLabel(i: Int) = if (i == numGroups - 2) terminalLabel else initialLabels(i + 1)

      for ((label, i) <- initialLabels.iterator.zipWithIndex) {
        mv.visitTryCatchBlock(label, nextLabel(i), nextLabel(i), jlIllegalArgExceptionRef.internalName)
      }
      for ((label, i) <- initialLabels.iterator.zipWithIndex) {
        mv.visitLabel(label)
        emitLambdaDeserializeIndy(groups(i).toIndexedSeq)
        mv.visitInsn(ARETURN)
      }
      mv.visitLabel(terminalLabel)
      emitLambdaDeserializeIndy(groups(numGroups - 1).toIndexedSeq)
      mv.visitInsn(ARETURN)
    }

    def run(): Unit = {
      while (true) {
        val item = q2.poll
        if (item.isPoison) {
          q3 add poison3
          return
        }
        else {
          try {
            val plainNode = item.plain.classNode
            localOptimizations(plainNode)
            val serializableLambdas = collectSerializableLambdas(plainNode)
            if (serializableLambdas.nonEmpty)
              addLambdaDeserialize(plainNode, serializableLambdas)
            addToQ3(item)
          } catch {
            case ex: Throwable =>
              println(s"Error while emitting ${item.plain.classNode.name}")
              throw ex
          }
        }
      }
    }

    private def addToQ3(item: Item2) = {

      def getByteArray(cn: asm.tree.ClassNode): Array[Byte] = {
        val cw = new CClassWriter(extraProc)
        cn.accept(cw)
        cw.toByteArray
      }

      val Item2(arrivalPos, SubItem2(mirror, mirrorFile), SubItem2(plain, plainFile)) = item

      val mirrorC = if (mirror == null) null else SubItem3(mirror.name, getByteArray(mirror), mirrorFile)
      val plainC  = SubItem3(plain.name, getByteArray(plain), plainFile)

      if (AsmUtils.traceSerializedClassEnabled && plain.name.contains(AsmUtils.traceSerializedClassPattern)) {
        if (mirrorC != null) AsmUtils.traceClass(mirrorC.jclassBytes)
        AsmUtils.traceClass(plainC.jclassBytes)
      }

      q3 add Item3(arrivalPos, mirrorC, plainC)
    }

  } // end of class BCodePhase.Worker2

  var arrivalPos: Int = 0

  /*
    *  A run of the BCodePhase phase comprises:
    *
    *    (a) set-up steps (most notably supporting maps in `BCodeTypes`,
    *        but also "the" writer where class files in byte-array form go)
    *
    *    (b) building of ASM ClassNodes, their optimization and serialization.
    *
    *    (c) tear down (closing the classfile-writer and clearing maps)
    *
    */
  def run(t: Tree): Unit = {
    this.tree = t

    // val bcodeStart = Statistics.startTimer(BackendStats.bcodeTimer)

    // val initStart = Statistics.startTimer(BackendStats.bcodeInitTimer)
    arrivalPos = 0 // just in case
    // scalaPrimitives.init()
    bTypes.intializeCoreBTypes()
    // Statistics.stopTimer(BackendStats.bcodeInitTimer, initStart)

    // initBytecodeWriter invokes fullName, thus we have to run it before the typer-dependent thread is activated.
    bytecodeWriter  = initBytecodeWriter()
    mirrorCodeGen   = new JMirrorBuilder

    val needsOutfileForSymbol = bytecodeWriter.isInstanceOf[ClassBytecodeWriter]
    buildAndSendToDisk(needsOutfileForSymbol)

    // closing output files.
    bytecodeWriter.close()
    // Statistics.stopTimer(BackendStats.bcodeTimer, bcodeStart)

    if (ctx.compilerCallback != null)
      ctx.compilerCallback.onSourceCompiled(sourceFile)

    /* TODO Bytecode can be verified (now that all classfiles have been written to disk)
      *
      * (1) asm.util.CheckAdapter.verify()
      *       public static void verify(ClassReader cr, ClassLoader loader, boolean dump, PrintWriter pw)
      *     passing a custom ClassLoader to verify inter-dependent classes.
      *     Alternatively,
      *       - an offline-bytecode verifier could be used (e.g. Maxine brings one as separate tool).
      *       - -Xverify:all
      *
      * (2) if requested, check-java-signatures, over and beyond the syntactic checks in `getGenericSignature()`
      *
      */
  }

  /*
    *  Sequentially:
    *    (a) place all ClassDefs in queue-1
    *    (b) dequeue one at a time from queue-1, convert it to ASM ClassNode, place in queue-2
    *    (c) dequeue one at a time from queue-2, convert it to byte-array,    place in queue-3
    *    (d) serialize to disk by draining queue-3.
    */
  private def buildAndSendToDisk(needsOutFolder: Boolean) = {

    feedPipeline1()
    // val genStart = Statistics.startTimer(BackendStats.bcodeGenStat)
    (new Worker1(needsOutFolder)).run()
    // Statistics.stopTimer(BackendStats.bcodeGenStat, genStart)

    (new Worker2).run()

    // val writeStart = Statistics.startTimer(BackendStats.bcodeWriteTimer)
    drainQ3()
    // Statistics.stopTimer(BackendStats.bcodeWriteTimer, writeStart)

  }

  /* Feed pipeline-1: place all ClassDefs on q1, recording their arrival position. */
  private def feedPipeline1() = {
    def gen(tree: Tree): Unit = {
      tree match {
        case EmptyTree            => ()
        case PackageDef(_, stats) => stats foreach gen
        case ValDef(name, tpt, rhs) => () // module val not emitted
        case cd: TypeDef         =>
          q1 add Item1(arrivalPos, cd, int.currentUnit)
          arrivalPos += 1
      }
    }
    gen(tree)
    q1 add poison1
  }

  /* Pipeline that writes classfile representations to disk. */
  private def drainQ3() = {

    def sendToDisk(cfr: SubItem3): Unit = {
      if (cfr != null){
        val SubItem3(jclassName, jclassBytes, jclassFile) = cfr
        bytecodeWriter.writeClass(jclassName, jclassName, jclassBytes, jclassFile)
      }
    }

    var moreComing = true
    // `expected` denotes the arrivalPos whose Item3 should be serialized next
    var expected = 0

    while (moreComing) {
      val incoming = q3.poll
      moreComing   = !incoming.isPoison
      if (moreComing) {
        val item = incoming
        sendToDisk(item.mirror)
        sendToDisk(item.plain)
        expected += 1
      }
    }

    // we're done
    assert(q1.isEmpty, s"Some ClassDefs remained in the first queue: $q1")
    assert(q2.isEmpty, s"Some classfiles remained in the second queue: $q2")
    assert(q3.isEmpty, s"Some classfiles weren't written to disk: $q3")

  }
  //} // end of class BCodePhase
}
