package org.broadinstitute.sting.queue.function

import org.broadinstitute.sting.queue.util._
import java.lang.annotation.Annotation
import org.broadinstitute.sting.commandline._
import java.io.File
import collection.JavaConversions._
import org.broadinstitute.sting.queue.function.scattergather.{SimpleTextGatherFunction, Gather}
import org.broadinstitute.sting.queue.{QSettings, QException}

/**
 * A command line that will be run in a pipeline.
 */
trait CommandLineFunction extends QFunction with Logging {
  def commandLine: String

  /** Default settings */
  var qSettings: QSettings = _

  /** Upper memory limit */
  var memoryLimit: Option[Int] = None

  /** Whether a job is restartable */
  var jobRestartable = true

  /** Directory to run the command in. */
  var commandDirectory: File = IOUtils.CURRENT_DIR

  /** Prefix for automatic job name creation */
  var jobNamePrefix: String = _

  /** The name name of the job */
  var jobName: String = _

  /** Job project to run the command */
  var jobProject: String = _

  /** Job queue to run the command */
  var jobQueue: String = _

  /** Temporary directory to write any files */
  var jobTempDir: File = new File(System.getProperty("java.io.tmpdir"))

  /** If true this function will run only if the jobs it is dependent on succeed. */
  var jobRunOnlyIfPreviousSucceed = true

  /** Files that this job should wait on before running. */
  @Input(doc="Explicit job dependencies", required=false)
  var jobDependencies: List[File] = Nil

  /** File to redirect any output.  Defaults to <jobName>.out */
  @Output(doc="File to redirect any output", required=false)
  @Gather(classOf[SimpleTextGatherFunction])
  var jobOutputFile: File = _

  /** File to redirect any errors.  Defaults to <jobName>.out */
  @Output(doc="File to redirect any errors", required=false)
  @Gather(classOf[SimpleTextGatherFunction])
  var jobErrorFile: File = _

  /** The complete list of fields on this CommandLineFunction. */
  lazy val functionFields: List[ArgumentSource] = ParsingEngine.extractArgumentSources(this.getClass).toList
  /** The @Input fields on this CommandLineFunction. */
  lazy val inputFields = functionFields.filter(source => ReflectionUtils.hasAnnotation(source.field, classOf[Input]))
  /** The @Output fields on this CommandLineFunction. */
  lazy val outputFields = functionFields.filter(source => ReflectionUtils.hasAnnotation(source.field, classOf[Output]))
  /** The @Argument fields on this CommandLineFunction. */
  lazy val argumentFields = functionFields.filter(source => ReflectionUtils.hasAnnotation(source.field, classOf[Argument]))

  /**
   * Returns set of directories required to run the command.
   * @return Set of directories required to run the command.
   */
  def jobDirectories = {
    var dirs = Set.empty[File]
    dirs += commandDirectory
    if (jobTempDir != null)
      dirs += jobTempDir
    dirs ++= inputs.map(_.getParentFile)
    dirs ++= outputs.map(_.getParentFile)
    dirs
  }

  /**
   * Returns the input files for this function.
   * @return Set[File] inputs for this function.
   */
  def inputs = getFieldFiles(inputFields)

  /**
   * Returns the output files for this function.
   * @return Set[File] outputs for this function.
   */
  def outputs = getFieldFiles(outputFields)

  /**
   * Gets the files from the fields.  The fields must be a File, a FileProvider, or a List or Set of either.
   * @param fields Fields to get files.
   * @return Set[File] for the fields.
   */
  private def getFieldFiles(fields: List[ArgumentSource]): Set[File] = {
    var files = Set.empty[File]
    for (field <- fields)
      files ++= getFieldFiles(field)
    files
  }

  /**
   * Returns true if all outputs already exist and are older that the inputs.
   * If there are no outputs then returns false.
   * @return true if all outputs already exist and are older that the inputs.
   */
  def upToDate = {
    val inputFiles = inputs
    val outputFiles = outputs.filterNot(file => (file == jobOutputFile || file == jobErrorFile))
    if (outputFiles.size > 0 && outputFiles.forall(_.exists)) {
      val maxInput = inputFiles.foldLeft(Long.MinValue)((date, file) => date.max(file.lastModified))
      val minOutput = outputFiles.foldLeft(Long.MaxValue)((date, file) => date.min(file.lastModified))
      maxInput < minOutput
    } else false
  }

  /**
   * Gets the files from the field.  The field must be a File, a FileProvider, or a List or Set of either.
   * @param fields Field to get files.
   * @return Set[File] for the field.
   */
  def getFieldFiles(field: ArgumentSource): Set[File] = {
    var files = Set.empty[File]
    CollectionUtils.foreach(getFieldValue(field), (fieldValue) => {
      val file = fieldValueToFile(field, fieldValue)
      if (file != null)
        files += file
    })
    files
  }

  /**
   * Gets the file from the field.  The field must be a File or a FileProvider and not a List or Set.
   * @param field Field to get the file.
   * @return File for the field.
   */
  def getFieldFile(field: ArgumentSource): File =
    fieldValueToFile(field, getFieldValue(field))

  /**
   * Converts the field value to a file.  The field must be a File or a FileProvider.
   * @param field Field to get the file.
   * @param value Value of the File or FileProvider or null.
   * @return Null if value is null, otherwise the File.
   * @throws QException if the value is not a File or FileProvider.
   */
  private def fieldValueToFile(field: ArgumentSource, value: Any): File = value match {
    case file: File => file
    case fileProvider: FileProvider => fileProvider.file
    case null => null
    case unknown => throw new QException("Non-file found.  Try removing the annotation, change the annotation to @Argument, or implement FileProvider: %s: %s".format(field.field, unknown))
  }

  /**
   * Resets the field to the temporary directory.
   * @param field Field to get and set the file.
   * @param tempDir new root for the file.
   */
  def resetFieldFile(field: ArgumentSource, tempDir: File): File = {
    getFieldValue(field) match {
      case file: File => {
        val newFile = IOUtils.resetParent(tempDir, file)
        setFieldValue(field, newFile)
        newFile
      }
      case fileProvider: FileProvider => {
        fileProvider.file = IOUtils.resetParent(tempDir, fileProvider.file)
        fileProvider.file
      }
      case null => null
      case unknown =>
        throw new QException("Unable to set file from %s: %s".format(field, unknown))
    }
  }

  /**
   * The function description in .dot files
   */
  override def dotString = jobName + " => " + commandLine

  /**
   * Sets all field values and makes them canonical so that the graph can
   * match the inputs of one function to the output of another using equals().
   */
  final override def freeze = {
    freezeFieldValues
    canonFieldValues
    super.freeze
  }

  /**
   * Sets all field values.
   */
  def freezeFieldValues = {
    if (jobNamePrefix == null)
      jobNamePrefix = qSettings.jobNamePrefix

    if (jobQueue == null)
      jobQueue = qSettings.jobQueue

    if (jobProject == null)
      jobProject = qSettings.jobProject

    if (memoryLimit.isEmpty && qSettings.memoryLimit.isDefined)
      memoryLimit = qSettings.memoryLimit

    if (qSettings.runJobsIfPrecedingFail)
      jobRunOnlyIfPreviousSucceed = false

    if (jobName == null)
      jobName = CommandLineFunction.nextJobName(jobNamePrefix)

    if (jobOutputFile == null)
      jobOutputFile = new File(jobName + ".out")

    commandDirectory = IOUtils.subDir(IOUtils.CURRENT_DIR, commandDirectory)
  }

  /**
   * Makes all field values canonical so that the graph can match the
   * inputs of one function to the output of another using equals().
   */
  def canonFieldValues = {
    for (field <- this.functionFields) {
      var fieldValue = this.getFieldValue(field)
      fieldValue = CollectionUtils.updated(fieldValue, canon).asInstanceOf[AnyRef]
      this.setFieldValue(field, fieldValue)
    }
  }

  /**
   * Set value to a uniform value across functions.
   * Base implementation changes any relative path to an absolute path.
   * @param value to be updated
   * @return the modified value, or a copy if the value is immutable
   */
  protected def canon(value: Any) = {
    value match {
      case file: File => absolute(file)
      case fileProvider: FileProvider => fileProvider.file = absolute(fileProvider.file); fileProvider
      case x => x
    }
  }

  /**
   * Returns the absolute path to the file relative to the job command directory.
   * @param file File to root relative to the command directory if it is not already absolute.
   * @return The absolute path to file.
   */
  private def absolute(file: File) = IOUtils.subDir(commandDirectory, file)

  /**
   * Repeats parameters with a prefix/suffix if they are set otherwise returns "".
   * Skips null, Nil, None.  Unwraps Some(x) to x.  Everything else is called with x.toString.
   * @param prefix Command line prefix per parameter.
   * @param params Traversable parameters.
   * @param suffix Optional suffix per parameter.
   * @param separator Optional separator per parameter.
   * @param format Format string if the value has a value
   * @return The generated string
   */
  protected def repeat(prefix: String, params: Traversable[_], suffix: String = "", separator: String = "", format: String = "%s") =
    params.filter(param => hasValue(param)).map(param => prefix + toValue(param, format) + suffix).mkString(separator)

  /**
   * Returns parameter with a prefix/suffix if it is set otherwise returns "".
   * Does not output null, Nil, None.  Unwraps Some(x) to x.  Everything else is called with x.toString.
   * @param prefix Command line prefix per parameter.
   * @param param Parameters to check for a value.
   * @param suffix Optional suffix per parameter.
   * @param format Format string if the value has a value
   * @return The generated string
   */
  protected def optional(prefix: String, param: Any, suffix: String = "", format: String = "%s") =
    if (hasValue(param)) prefix + toValue(param, format) + suffix else ""

  /**
   * Returns fields that do not have values which are required.
   * @return List[String] names of fields missing values.
   */
  def missingFields: List[String] = {
    val missingInputs = missingFields(inputFields, classOf[Input])
    val missingOutputs = missingFields(outputFields, classOf[Output])
    val missingArguments = missingFields(argumentFields, classOf[Argument])
    (missingInputs | missingOutputs | missingArguments).toList.sorted
  }

  /**
   * Returns fields that do not have values which are required.
   * @param sources Fields to check.
   * @param annotation Annotation.
   * @return Set[String] names of fields missing values.
   */
  private def missingFields(sources: List[ArgumentSource], annotation: Class[_ <: Annotation]): Set[String] = {
    var missing = Set.empty[String]
    for (source <- sources) {
      if (isRequired(source, annotation))
        if (!hasFieldValue(source))
          if (!exclusiveOf(source, annotation).exists(otherSource => hasFieldValue(otherSource)))
            missing += "@%s: %s - %s".format(annotation.getSimpleName, source.field.getName, doc(source, annotation))
    }
    missing
  }

  /**
   * Scala sugar type for checking annotation required and exclusiveOf.
   */
  private type ArgumentAnnotation = {
    def required(): Boolean
    def exclusiveOf(): String
    def doc(): String
  }

  /**
   * Returns the isRequired value from the field.
   * @param field Field to check.
   * @param annotation Annotation.
   * @return the isRequired value from the field annotation.
   */
  private def isRequired(field: ArgumentSource, annotation: Class[_ <: Annotation]) =
    ReflectionUtils.getAnnotation(field.field, annotation).asInstanceOf[ArgumentAnnotation].required

  /**
   * Returns an array of ArgumentSources from functionFields listed in the exclusiveOf of the original field
   * @param field Field to check.
   * @param annotation Annotation.
   * @return the Array[ArgumentSource] that may be set instead of the field.
   */
  private def exclusiveOf(field: ArgumentSource, annotation: Class[_ <: Annotation]) =
    ReflectionUtils.getAnnotation(field.field, annotation).asInstanceOf[ArgumentAnnotation].exclusiveOf
            .split(",").map(_.trim).filter(_.length > 0)
            .map(fieldName => functionFields.find(fieldName == _.field.getName) match {
      case Some(x) => x
      case None => throw new QException("Unable to find exclusion field %s on %s".format(fieldName, this.getClass.getSimpleName))
    })

  /**
   * Returns the doc value from the field.
   * @param field Field to check.
   * @param annotation Annotation.
   * @return the doc value from the field annotation.
   */
  private def doc(field: ArgumentSource, annotation: Class[_ <: Annotation]) =
    ReflectionUtils.getAnnotation(field.field, annotation).asInstanceOf[ArgumentAnnotation].doc

  /**
   * Returns true if the field has a value.
   * @param source Field to check for a value.
   * @return true if the field has a value.
   */
  protected def hasFieldValue(source: ArgumentSource) = this.hasValue(this.getFieldValue(source))

  /**
   * Returns false if the value is null or an empty collection.
   * @param value Value to test for null, or a collection to test if it is empty.
   * @return false if the value is null, or false if the collection is empty, otherwise true.
   */
  private def hasValue(param: Any) = CollectionUtils.isNotNullOrNotEmpty(param)

  /**
   * Returns "" if the value is null or an empty collection, otherwise return the value.toString.
   * @param value Value to test for null, or a collection to test if it is empty.
   * @param format Format string if the value has a value
   * @return "" if the value is null, or "" if the collection is empty, otherwise the value.toString.
   */
  private def toValue(param: Any, format: String): String = if (CollectionUtils.isNullOrEmpty(param)) "" else
  param match {
    case Some(x) => format.format(x)
    case x => format.format(x)
  }

  /**
   * Gets the value of a field.
   * @param source Field to get the value for.
   * @return value of the field.
   */
  def getFieldValue(source: ArgumentSource) = ReflectionUtils.getValue(invokeObj(source), source.field)

  /**
   * Gets the value of a field.
   * @param source Field to set the value for.
   * @return value of the field.
   */
  def setFieldValue(source: ArgumentSource, value: Any) = ReflectionUtils.setValue(invokeObj(source), source.field, value)

  /**
   * Walks gets the fields in this object or any collections in that object
   * recursively to find the object holding the field to be retrieved or set.
   * @param source Field find the invoke object for.
   * @return Object to invoke the field on.
   */
  private def invokeObj(source: ArgumentSource) = source.parentFields.foldLeft[AnyRef](this)(ReflectionUtils.getValue(_, _))
}

/**
 * A command line that will be run in a pipeline.
 */
object CommandLineFunction {
  /** Job index counter for this run of Queue. */
  private var jobIndex = 0

  /**
   * Returns the next job name using the prefix.
   * @param prefix Prefix of the job name.
   * @return the next job name.
   */
  private def nextJobName(prefix: String) = {
    jobIndex += 1
    prefix + "-" + jobIndex
  }
}
