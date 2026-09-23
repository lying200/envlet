// ENV-23 investigation: run only in an isolated IDEA profile with PythonCore and Pythonid 262.
// Authored fixture: ~/code/envlet-python-smoke; .envrc approved explicitly for this test.
// Compares Envlet off/on, then an explicit fixture-only Python Run environment control.
// SDK/run configuration construction is test scaffolding, not Envlet implementation.
// Output contains only this fixture's known values; never use against ordinary projects.
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
 assert ready():label
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
 write {
  def manager=com.intellij.openapi.module.ModuleManager.getInstance(project)
  module=manager.modules ? manager.modules[0] : manager.newModule(root.resolve('python-smoke.iml'),'PYTHON_MODULE')
  def model=com.intellij.openapi.roots.ModuleRootManager.getInstance(module).modifiableModel
  if(!model.contentEntries) model.addContentEntry(VfsUtil.pathToUrl(project.basePath))
  model.inheritSdk();model.commit()
 }
 def service=project.getService(plugin.pluginClassLoader.loadClass('io.github.salatmaster.direnv.DirenvService'))
 def settings=project.getService(plugin.pluginClassLoader.loadClass('io.github.salatmaster.direnv.settings.DirenvSettings'))
 settings.state.autoGoToolchain=false;settings.state.autoRustToolchain=false
 waitUntil('direnv load') { service.cachedFor(root)!=null }
 Thread.sleep(5000)
 record('auto.projectSdk='+ProjectRootManager.getInstance(project).projectSdk?.sdkType?.name)
 module=com.intellij.openapi.module.ModuleManager.getInstance(project).modules.find { !it.disposed }
 record('auto.moduleSdk='+(module==null?'no-module':com.intellij.openapi.roots.ModuleRootManager.getInstance(module).sdk?.sdkType?.name))
 def exe=root.resolve('.venv/bin/python').toString()
 def script=root.resolve('probe.py').toString()
 def linuxRoot='/home/echoyn/code/envlet-python-smoke'
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
 runGeneric(false);runGeneric(true)
 def pythonClass={ String name ->
  for(def descriptor:PluginManagerCore.pluginSet.enabledModules) {
   try { return descriptor.pluginClassLoader.loadClass(name) } catch(ClassNotFoundException ignored) {}
  }
  throw new ClassNotFoundException(name)
 }
 def type=pythonClass('com.jetbrains.python.sdk.PythonSdkType').getInstance()
 def factoryClass=pythonClass('com.jetbrains.python.run.PythonInterpreterTargetEnvironmentFactory')
 record('python.targetFactories='+factoryClass.Companion.getEP_NAME().extensionList.collect { it.class.name }.join(','))
 def flavor=pythonClass('com.jetbrains.python.sdk.flavors.UnixPythonSdkFlavor').getInstance()
 def flavorData=pythonClass('com.jetbrains.python.sdk.flavors.PyFlavorAndData').constructors[0].newInstance(pythonClass('com.jetbrains.python.sdk.flavors.PyFlavorData$Empty').INSTANCE,flavor)
 def data=pythonClass('com.jetbrains.python.sdk.PythonSdkAdditionalData').constructors.find { it.parameterCount==2 }.newInstance(flavorData,root)
 def distribution=com.intellij.execution.wsl.WslDistributionManager.getInstance().getOrCreateDistributionByMsId('legion-wsl')
 assert distribution!=null
 def target=new com.intellij.execution.wsl.target.WslTargetEnvironmentConfiguration(distribution)
 def targetData=pythonClass('com.jetbrains.python.target.PyTargetAwareAdditionalData').constructors.find { it.parameterCount==2 }.newInstance(data,target)
 targetData.setInterpreterPath(linuxRoot+'/.venv/bin/python')
 data=targetData
 def sdk=ProjectJdkTable.instance.createSdk('Envlet disposable Python '+System.currentTimeMillis(),type)
 write {
  def m=sdk.sdkModificator;m.homePath=data.sdkId;m.sdkAdditionalData=data;m.versionString='Python 3.14.7';m.commitChanges()
  ProjectJdkTable.instance.addJdk(sdk)
  // SDK attached to this run configuration only.
 }
 record('sdk.data='+sdk.sdkAdditionalData.class.name)
 record('python.targetRequest='+factoryClass.findPythonTargetInterpreter(sdk,project).class.name)
 def configurationType=pythonClass('com.jetbrains.python.run.PythonConfigurationType').getInstance()
 def factory=configurationType.factory
 def cfg=factory.createTemplateConfiguration(project)
 cfg.name='Envlet Python probe';cfg.setSdk(sdk);cfg.setUseModuleSdk(false)
 cfg.setScriptName(script);cfg.setWorkingDirectory(root.toString());cfg.setAddContentRoots(false);cfg.setAddSourceRoots(false)
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
   record('pythonRun.'+label+'.at='+t.stackTrace.take(5).join('|'))
  }
 }
 runPython(false);runPython(true)
 cfg.setEnvs([ENVLET_PYTHON_TEST:'fixture-only',PYTHONPATH:linuxRoot+'/extras'])
 runPython(true,'explicit-control')
 settings.state.enabled=true
 record('FINISHED')
} catch(Throwable t) {
 record('ERROR='+t.class.name)
 record('ERROR.at='+t.stackTrace.take(6).join('|'));record('ERROR.script='+t.stackTrace.find { it.fileName?.endsWith('.groovy') })
}
