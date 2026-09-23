// ENV-24–28: isolated IDEA/WSL Python acceptance, including SDK reopen and approval recovery.
// Start scripts/python-smoke-fixture.py control alongside this script; see validation-env24.md.
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import java.nio.file.Paths
import java.nio.file.Files
import java.util.concurrent.TimeUnit

def root=Paths.get(System.getProperty('envlet.validation.project'))
def report=new File(System.getProperty('envlet.validation.result'))
report.text='STARTING\n'
def record={ report.append(it+'\n') }
def waitUntil={ label, Closure ready ->
 def end=System.nanoTime()+TimeUnit.SECONDS.toNanos(90)
 while(!ready() && System.nanoTime()<end) Thread.sleep(200)
 if(!ready()) { record('WAIT_TIMEOUT='+label); assert false:label }
}
def write={ Closure body -> ApplicationManager.application.invokeAndWait({ ApplicationManager.application.runWriteAction(body as Runnable) } as Runnable) }
try {
 def plugin=PluginManagerCore.getPlugin(PluginId.getId('io.github.lying200.envlet'))
 def python=PluginManagerCore.getPlugin(PluginId.getId('PythonCore'))
 record('envlet='+plugin.version);record('python='+python?.version);assert python?.enabled
 record('python.pro='+PluginManagerCore.getPlugin(PluginId.getId('Pythonid'))?.version)
 def project=ProjectManagerEx.instanceEx.openProject(root,OpenProjectTask.build().withForceOpenInNewFrame(true))
 assert project!=null
 def module
 waitUntil('fixture module loaded') {
  ApplicationManager.application.runReadAction({
   module=com.intellij.openapi.module.ModuleManager.getInstance(project).modules.find { !it.isDisposed() }
  } as Runnable)
  module!=null
 }
 record('fixture-module-loaded=true')
 def service=project.getService(plugin.pluginClassLoader.loadClass('io.github.salatmaster.direnv.DirenvService'))
 def settings=project.getService(plugin.pluginClassLoader.loadClass('io.github.salatmaster.direnv.settings.DirenvSettings'))
 settings.state.autoGoToolchain=false;settings.state.autoRustToolchain=false
 waitUntil('direnv load') { service.cachedFor(root)!=null }
 Thread.sleep(5000)
 record('auto.projectSdk='+ProjectRootManager.getInstance(project).projectSdk?.sdkType?.name)
 module=com.intellij.openapi.module.ModuleManager.getInstance(project).modules.find { !it.isDisposed() }
 record('auto.moduleSdk='+(module==null?'no-module':com.intellij.openapi.roots.ModuleRootManager.getInstance(module).sdk?.sdkType?.name))
 def exe=root.resolve('.venv/bin/python').toString()
 def script=root.resolve('probe.py').toString()
 def linuxRoot=System.getProperty('envlet.validation.targetProject')
 assert linuxRoot!=null
 def runGeneric={ boolean enabled ->
  settings.state.enabled=enabled
  def output=root.resolve('generic-'+enabled+'.json');Files.deleteIfExists(output)
  def cmd=new GeneralCommandLine(exe,linuxRoot+'/probe.py',linuxRoot+'/'+output.fileName).withWorkingDirectory(root)
  def proc=cmd.createProcess()
  def done=proc.waitFor(30,TimeUnit.SECONDS)
  if(!done) proc.destroyForcibly()
  record('generic.'+enabled+'.exit='+(done?proc.exitValue():'timeout'))
  record('generic.'+enabled+'.result='+(Files.exists(output)?Files.readString(output):'absent'))
 }
 def pythonClass={ String name ->
  for(def descriptor:PluginManagerCore.pluginSet.enabledModules) {
   try { return descriptor.pluginClassLoader.loadClass(name) } catch(ClassNotFoundException ignored) {}
  }
  throw new ClassNotFoundException(name)
 }
 waitUntil('automatic Python SDK') {
  def selected=ProjectRootManager.getInstance(project).projectSdk
  selected!=null && selected.name.startsWith('Envlet Python')
 }
 def sdk=ProjectRootManager.getInstance(project).projectSdk
 runGeneric(false);runGeneric(true)
 record('auto.sdk='+sdk.name)
 record('sdk.data='+sdk.sdkAdditionalData.class.name)
 def factoryClass=pythonClass('com.jetbrains.python.run.PythonInterpreterTargetEnvironmentFactory')
 record('python.targetRequest='+factoryClass.findPythonTargetInterpreter(sdk,project).class.name)
 def configurationType=pythonClass('com.jetbrains.python.run.PythonConfigurationType').getInstance()
 def factory=configurationType.factory
 def cfg=factory.createTemplateConfiguration(project)
 cfg.setModule(module)
 cfg.name='Envlet Python probe';cfg.setSdk(sdk);cfg.setUseModuleSdk(false)
 cfg.setScriptName(script);cfg.setWorkingDirectory(root.toString());cfg.setAddContentRoots(false);cfg.setAddSourceRoots(false)
 def distribution=com.intellij.execution.wsl.WslPath.parseWindowsUncPath(root.toString()).distribution
 def targetDataClass=pythonClass('com.jetbrains.python.target.PyTargetAwareAdditionalData')
 def checkSdkMappings={ String label ->
  def data=cfg.getSdk().sdkAdditionalData
  assert targetDataClass.getPathsAddedByUser(data).isEmpty() // probe roots must not become runtime paths
  def mappings=data.pathMappings.pathMappings
  // A newly published SDK may still await the platform's first mapping refresh.
  mappings.each { mapping ->
   assert mapping.remoteRoot==distribution.getWslPath(mapping.localRoot):'Incorrect SDK root mapping: '+label
  }
  def library=com.intellij.openapi.roots.ModuleRootManager.getInstance(module).orderEntries.find {
   it instanceof com.intellij.openapi.roots.LibraryOrderEntry && it.libraryName=='Envlet Python search paths'
  }?.library
  assert library!=null
  assert library.getFiles(com.intellij.openapi.roots.OrderRootType.CLASSES).length==0
  def indexRoots=library.getFiles(com.intellij.openapi.roots.OrderRootType.SOURCES)
  assert indexRoots.any { it.toNioPath()==root.resolve('extras') }
  assert indexRoots.any { !it.toNioPath().startsWith(root) }
  record('sdk-mappings.'+label+'=true')
 }
 checkSdkMappings('initial')
 def executor=DefaultRunExecutor.getRunExecutorInstance()
 def runPython={ boolean enabled, String label=String.valueOf(enabled) ->
  settings.state.enabled=enabled
  def output=root.resolve('python-run-'+label+'.json');Files.deleteIfExists(output)
  cfg.setScriptParameters(linuxRoot+'/'+output.fileName)
  try {
   def execution=ExecutionEnvironmentBuilder.create(executor,cfg).build()
   def state=cfg.getState(executor,execution)
   record('pythonRun.'+label+'.state='+state.class.name)
   def result=state.execute(executor)
   def handler=result.processHandler
   handler.startNotify()
   boolean done=handler.waitFor(45000)
   if(!done) handler.destroyProcess()
   record('pythonRun.'+label+'.exit='+(done?handler.exitCode:'timeout'))
   record('pythonRun.'+label+'.result='+(Files.exists(output)?Files.readString(output):'absent'))
  } catch(Throwable t) {
   record('pythonRun.'+label+'.error='+t.class.name)
   def cause=t; boolean unsetRejected=false
   while(cause!=null) { unsetRejected |= cause.message?.contains('cannot remove inherited environment variables') ?: false; cause=cause.cause }
   record('pythonRun.'+label+'.unsetRejected='+unsetRejected)
   record('pythonRun.'+label+'.at='+t.stackTrace.take(5).join('|'))
  }
 }
 runPython(false);runPython(true)
 cfg.setEnvs([ENVLET_PYTHON_TEST:'fixture-only',PYTHONPATH:linuxRoot+'/extras'])
 runPython(true,'explicit-control')
 cfg.setEnvs([ENVLET_PYTHON_TEST:'explicit-choice'])
 runPython(true,'explicit-precedence')
 assert Files.readString(root.resolve('python-run-explicit-precedence.json')).contains('"env": false')
 settings.state.enabled=true
 cfg.setEnvs([:])
 cfg.setEnvFilePaths([root.resolve('run.env').toString()])
 runPython(true,'env-file')
 def fileResult=Files.readString(root.resolve('python-run-env-file.json'))
 assert fileResult.contains('"envfile_override": true')
 assert fileResult.contains('"file_dependency": true')
 assert fileResult.contains('"dependency": false') // explicit PYTHONPATH replaces root-only imports
 cfg.setEnvs([ENVLET_ENVFILE_MODE:'direct'])
 runPython(true,'env-file-direct')
 assert Files.readString(root.resolve('python-run-env-file-direct.json')).contains('"direct_override": true')
 cfg.setEnvFilePaths([])
 cfg.setEnvs([PYTHONPATH:linuxRoot+'/file-extras'])
 runPython(true,'direct-path')
 def directPath=Files.readString(root.resolve('python-run-direct-path.json'))
 assert directPath.contains('"dependency": false') && directPath.contains('"file_dependency": true')
 cfg.setEnvs([PYTHONPATH:''])
 runPython(true,'empty-path')
 assert Files.readString(root.resolve('python-run-empty-path.json')).contains('"dependency": false')
 write {
  def model=com.intellij.openapi.roots.ModuleRootManager.getInstance(module).modifiableModel
  def entry=model.contentEntries.find { it.file?.toNioPath()==root }
  def url=VfsUtil.pathToUrl(root.resolve('ide-helper').toString())
  if(!entry.sourceFolders.any { it.url==url }) entry.addSourceFolder(url,false)
  model.commit()
 }
 cfg.setAddSourceRoots(true)
 cfg.setEnvs([PYTHONPATH:linuxRoot+'/file-extras'])
 runPython(true,'helpers')
 def helperResult=Files.readString(root.resolve('python-run-helpers.json'))
 assert helperResult.contains('"helper_dependency": true') && helperResult.contains('"dependency": false')
 cfg.setAddSourceRoots(false)
 cfg.setEnvs([:])
 cfg.setWorkingDirectory(root.resolve('replacement').toString())
 runPython(true,'replacement-child')
 def replaced=Files.readString(root.resolve('python-run-replacement-child.json'))
 assert replaced.contains('"dependency": false') && replaced.contains('"file_dependency": true')
 record('pythonpath-replacement=true')
 cfg.setWorkingDirectory(root.resolve('shared').toString())
 runPython(true,'shared-first')
 assert Files.readString(root.resolve('python-run-shared-first.json')).contains('"env": true')
 // Script is in root; this module exists only in the working directory. No content/source roots.
 assert Files.readString(root.resolve('python-run-shared-first.json')).contains('"cwd_dependency": true')
 record('pythonpath-cwd-import=true')
 cfg.setWorkingDirectory(root.resolve('blocked').toString())
 runPython(true,'blocked-child')
 assert Files.readString(root.resolve('python-run-blocked-child.json')).contains('"env": false')
 cfg.setWorkingDirectory(root.toString())
 // Repeated actual launches cover the background updater that previously saved bad UNC mappings.
 for(int i=0;i<3;i++) {
  Thread.sleep(2000)
  checkSdkMappings('background-'+i)
  runPython(true,'background-'+i)
  assert Files.readString(root.resolve('python-run-background-'+i+'.json')).contains('"env": true')
 }
 def controls=Files.createDirectories(root.resolve('.control'))
 def signal={ String step ->
  Files.writeString(controls.resolve('ready-'+step),'ready')
  waitUntil('controller '+step) { Files.exists(controls.resolve('done-'+step)) }
 }
 signal('switch')
 waitUntil('Python switches venv') {
  def current=ProjectRootManager.getInstance(project).projectSdk
  current?.sdkAdditionalData?.interpreterPath?.endsWith('/.venv-next/bin/python')
 }
 cfg.setSdk(ProjectRootManager.getInstance(project).projectSdk)
 checkSdkMappings('switched')
 record('sdk.switch=true')
 runPython(true,'switched')
 assert Files.readString(root.resolve('python-run-switched.json')).contains('/.venv-next/bin/python')
 signal('deny')
 waitUntil('revoked root') { service.cachedFor(root)==null && service.state().class.simpleName in ['Blocked','Denied'] }
 checkSdkMappings('revoked')
 runPython(true,'revoked')
 assert Files.readString(root.resolve('python-run-revoked.json')).contains('"env": false')
 signal('allow')
 waitUntil('approved root') { service.cachedFor(root)!=null }
 cfg.setWorkingDirectory(root.resolve('shared').toString())
 runPython(true,'reapproved-child')
 assert Files.readString(root.resolve('python-run-reapproved-child.json')).contains('"env": true')
 cfg.setWorkingDirectory(root.toString())
 signal('unset')
 waitUntil('unset loaded') { def e=service.cachedFor(root); e!=null && e.entries.containsKey('HOME') && e.entries.get('HOME')==null }
 runPython(true,'unsupported-unset')
 assert !Files.exists(root.resolve('python-run-unsupported-unset.json'))
 assert report.text.contains('pythonRun.unsupported-unset.unsetRejected=true')
 cfg.setEnvFilePaths([root.resolve('unset.env').toString()])
 runPython(true,'env-file-unset')
 def unsetResult=Files.readString(root.resolve('python-run-env-file-unset.json'))
 assert unsetResult.contains('"envfile_unset": true')
 assert unsetResult.contains('"envfile_override": true')
 cfg.setEnvFilePaths([])
 record('env-file-priority-and-unset=true')
 checkSdkMappings('final')
 com.intellij.openapi.project.DumbService.getInstance(project).waitForSmartMode()
 ApplicationManager.application.runReadAction({
  def file=com.intellij.psi.PsiManager.getInstance(project).findFile(VfsUtil.findFile(root.resolve('probe.py'),false))
  def imports=com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(file,pythonClass('com.jetbrains.python.psi.PyImportElement'))
  def dependency=imports.find { it.importedQName?.toString()=='envlet_fixture_dependency' }
  assert dependency!=null && dependency.resolve()!=null // source-only library still supports Python resolution
 } as Runnable)
 record('index-import-resolution=true')
 signal('restore')
 waitUntil('restored') { def e=service.cachedFor(root); e!=null && !(e.entries.containsKey('HOME') && e.entries.get('HOME')==null) }
 cfg.setAddSourceRoots(true)
 cfg.setEnvs([PYTHONPATH:linuxRoot+'/file-extras'])
 runPython(true,'helpers-after-refresh')
 def refreshedHelpers=Files.readString(root.resolve('python-run-helpers-after-refresh.json'))
 assert refreshedHelpers.contains('"helper_dependency": true') && refreshedHelpers.contains('"dependency": false')
 cfg.setAddSourceRoots(false);cfg.setEnvs([:])
 def javaType=pythonClass('com.intellij.openapi.projectRoots.JavaSdk').getInstance()
 def javaSdk=javaType.createJdk('Envlet validation Java SDK '+System.nanoTime(),System.getProperty('java.home'),false)
 write {
  ProjectJdkTable.getInstance().addJdk(javaSdk)
  ProjectRootManager.getInstance(project).projectSdk=javaSdk
 }
 assert ProjectRootManager.getInstance(project).projectSdkName==javaSdk.name
 service.scheduleReload(root)
 Thread.sleep(5000)
 assert ProjectRootManager.getInstance(project).projectSdkName==javaSdk.name
 record('java-sdk-preserved=true')
 write { ProjectRootManager.getInstance(project).projectSdk=sdk;ProjectJdkTable.getInstance().removeJdk(javaSdk) }
 record('nested-reload-and-approval=true')
 assert Files.readString(root.resolve('python-run-true.json')).contains('\"env\": true')
 assert Files.readString(root.resolve('python-run-true.json')).contains('\"dependency\": true')
 record('ACCEPTANCE=true')
 ApplicationManager.application.invokeAndWait({ project.save() } as Runnable)
 record('FINISHED')
} catch(Throwable t) {
 record('ERROR='+t.class.name)
 def cause=t.cause
 while(cause!=null) { record('ERROR.cause='+cause.class.name+' '+cause.stackTrace.take(2).join('|')); cause=cause.cause }
 record('ERROR.at='+t.stackTrace.take(6).join('|'));record('ERROR.script='+t.stackTrace.find { it.fileName?.endsWith('.groovy') })
}
