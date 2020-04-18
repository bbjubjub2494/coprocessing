package dotty.tastydoc

import scala.tasty.inspector._

import dotty.tastydoc.representations._

import java.io._

object Main {

  /** Call main with the following arguments to produce Markdown documentation files:
   * * **[--syntax SYNTAX]** Syntax for parsing user documentation (either *wiki or markdown*)
   * * **[--packagestolink REGEXES...]** Regexes of packages or entities (example: `scala.collection.*`). Only the types with a path matching these regexes will produce links in the documentation files
   * * **[--classpath URI]** Extra classpath for input files
   * * **[-i FILES...]** TASTy files
   * * **[-d DIRECTORIES...]** Directories to recursively find TASTy files
   */
  def main(args: Array[String]): Unit = {
    val userDocSyntax = {
      val idx = args.indexOf("--syntax")
      if(idx >= 0 && args.size > idx + 1){
        if(args(idx + 1) == "markdown"){
          "markdown"
        }else if(args(idx + 1) == "wiki"){
          "wiki"
        }else{
          println("Wrong parameter for -syntax. Using default: wiki")
          "wiki"
        }
      }else{
        "wiki"
      }
    }

    val packagesToLink = {
      val idx = args.indexOf("--packagestolink")
      if(idx >= 0 && args.size > idx + 1){
        args.drop(idx + 1).takeWhile(! _.startsWith("-")).toList
      }else{
        Nil
      }
    }


    val extraClasspath = {
      val idx = args.indexOf("--classpath")
      if(idx >= 0 && args.size > idx + 1){
        args(idx + 1)
      }else{
        "."
      }
    }

    val classesI = {
      val idx = args.indexOf("-i")
      if(idx >= 0 && args.size > idx + 1){
        args.drop(idx + 1).takeWhile(_!= "-d").toList
      }else{
        Nil
      }
    }

    val classesD = {
      val idx = args.indexOf("-d")
      if(idx >= 0 && args.size > idx + 1){
        val dirs = args.drop(idx + 1).takeWhile(_!= "-i").toList

        def findTastyFiles(f: File): Array[String] = {
          val allFiles = f.listFiles
          val tastyFiles = allFiles.filter(f => f.getName.endsWith(".tasty")).map(x => x.getPath.stripPrefix(extraClasspath + "/").stripSuffix(".tasty").replaceAll("\\/", "."))
          tastyFiles ++ allFiles.filter(_.isDirectory).flatMap(findTastyFiles)
        }

        dirs.flatMap{d =>
          val f = new File(extraClasspath + "/" + d)
          if(f.exists){
            findTastyFiles(f)
          }else{
            println("[warn] Ignoring invalid directory name: " + d)
            Nil
          }
        }
      }else{
        Nil
      }
    }

    val classes = classesI ++ classesD

    if (classes.isEmpty) {
      println("Dotty Tastydoc: No classes were passed as argument")
    } else {
      println("Running Dotty Tastydoc on: " + classes.mkString(" "))
      val mutablePackagesMap: scala.collection.mutable.HashMap[String, EmulatedPackageRepresentation] = new scala.collection.mutable.HashMap[String, EmulatedPackageRepresentation]()
      val tc = new TastydocInspector(mutablePackagesMap)
      tc.inspect(extraClasspath, classes)

      representations.setSubClasses(mutablePackagesMap)

      val docPrinter = new DocPrinter(mutablePackagesMap, userDocSyntax, packagesToLink)

      mutablePackagesMap.foreach((_, v) => docPrinter.traverseRepresentation(v))
    }
  }
}
