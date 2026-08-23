package ml.luxinfine.gradle

import java.nio.file.Files
import java.nio.file.Paths
import java.util.stream.Collectors

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion

class MultiJavaForgePlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        project.pluginManager.apply('forge')

        configureJava(project)
        configureJars(project)
        configureSplitter(project)

        project.afterEvaluate {
            configureManifest(project)
            configureReobf(project)
            configureSplitterTasks(project)
            configureMultiBuild(project)
        }
    }

    private static void configureJava(Project project) {
        project.tasks.withType(JavaCompile).configureEach {
            options.encoding = 'UTF-8'
        }
        project.tasks.named('compileJava', JavaCompile) {
            options.release = 8
        }
    }

    private static void configureJars(Project project) {
        project.tasks.named('jar', Jar) {
            archiveClassifier = 'j8'
        }

        project.tasks.register('compileJava25', JavaCompile) {
            source = project.sourceSets.main.java
            classpath = project.sourceSets.main.compileClasspath
            destinationDirectory = project.layout.buildDirectory.dir('classes/java25/main')
            javaCompiler = project.javaToolchains.compilerFor {
                languageVersion = JavaLanguageVersion.of(25)
            }
            options.release = 25
            options.encoding = 'UTF-8'
        }

        project.tasks.register('jarJava25', Jar) {
            dependsOn 'compileJava25'

            from(project.layout.buildDirectory.dir('classes/java25/main'))
            from(project.sourceSets.main.resources)

            archiveClassifier = 'j25'
        }
    }

    private static void configureManifest(Project project) {
        if (!project.hasProperty('manifestAttributes'))
            return

        def manifestAttributes = project.ext.manifestAttributes

        if (manifestAttributes.isEmpty())
            return

        project.tasks.named('jar', Jar) {
            manifest {
                attributes manifestAttributes
            }
        }

        project.tasks.named('jarJava25', Jar) {
            manifest {
                attributes manifestAttributes
            }
        }
    }

    private static void configureSplitter(Project project) {
        def splitterRepo = Paths.get(System.getProperty('user.home'), 'IDEA Projects', 'JarSplitter').toAbsolutePath()
        def splitterJar = splitterRepo.resolve('Main/JarSplitter-3.0.jar')
        def mappingsFile = splitterRepo.resolve('mcp2srg.srg')

        project.configurations {
            jarSplitterDependencies {
                canBeConsumed = false
                canBeResolved = true
                extendsFrom project.configurations.implementation
            }
        }

        project.ext.multiJavaForgeCreateSplitterTask = {
            String taskName,
            def jarTask ->
                project.tasks.register(taskName, JavaExec) {
                    dependsOn 'reobf'
                    group = 'build'
                    classpath = project.files(splitterJar)

                    doFirst {
                        def builtJar = jarTask.archiveFile.get().asFile
                        def depsFile = Files.createTempFile('JarSplitter', '.tmp')
                        def depends = new HashSet<String>()

                        try {
                            project.configurations.jarSplitterDependencies.resolve().each {depends.add(it.toString())}
                        } catch (Throwable t) {
                            t.printStackTrace()
                        }

                        def propertiesList = new ArrayList<String>()
                        def properties = new Properties()

                        project.file('gradle.properties').withInputStream {
                            properties.load(it)
                        }
                        properties.propertyNames().each {
                            propertiesList.add(it.toString() + '=' + properties.getProperty(it.toString()))
                        }

                        propertiesList.add('BuildPath=' + builtJar.absolutePath)
                        propertiesList.add('SourcesDir=' + project.sourceSets.main.java.srcDirs[0])
                        propertiesList.add('DependenciesPaths=' + depends.stream().filter {
                                                    it.toString().endsWith('.jar')
                                                }.collect(Collectors.joining(';')))
                        propertiesList.add('MCMappingsPath=' + mappingsFile)

                        Files.write(depsFile, propertiesList)

                        args = [depsFile.toString()]

                        project.logger.lifecycle('')
                        project.logger.lifecycle('========================================')
                        project.logger.lifecycle('Running JarSplitter')
                        project.logger.lifecycle("Input: ${builtJar}")
                        project.logger.lifecycle('========================================')
                    }
                }
        }
    }

    private static void configureReobf(Project project) {
        def reobfTask = project.tasks.getByName('reobf')
        def java8Jar = project.tasks.getByName('jar')
        def java25Jar = project.tasks.getByName('jarJava25')

        project.logger.lifecycle('=== Adding jars to reobf ===')

        reobfTask.reobf(java8Jar)
        reobfTask.reobf(java25Jar) { artifactSpec ->
            artifactSpec.setClasspath(project.sourceSets.main.compileClasspath)
        }

        project.logger.lifecycle("Reobf artifacts count: " + reobfTask.getObfuscated().size())

        reobfTask.getObfuscated().each { artifact ->
            project.logger.lifecycle("  Input: ${artifact.getToObf()}")
            project.logger.lifecycle("  Output: ${artifact.getFile()}")
            project.logger.lifecycle("  Classpath: " + (artifact.classpath != null ? artifact.classpath.files.size() + ' files' : 'null'))
        }
    }

    private static void configureSplitterTasks(Project project) {
        def java8Jar = project.tasks.getByName('jar')
        def java25Jar = project.tasks.getByName('jarJava25')
        def useSplitterJ8 = project.ext.multiJavaForgeCreateSplitterTask('useSplitterJ8', java8Jar)
        def useSplitterJ25 = project.ext.multiJavaForgeCreateSplitterTask('useSplitterJ25', java25Jar)
        useSplitterJ8.configure {
            dependsOn project.tasks.named('reobf')
        }
        useSplitterJ25.configure {
            dependsOn project.tasks.named('reobf')
        }
    }

    private static void configureMultiBuild(Project project) {
        project.tasks.register('multiBuild') {
            dependsOn 'useSplitterJ8'
            dependsOn 'useSplitterJ25'

            group = 'build'
            description = 'Builds Java 8 and Java 25 versions, reobfs them and runs JarSplitter'

            doLast {
                def java8Jar = project.tasks.named('jar')
                def java25Jar = project.tasks.named('jarJava25')
                project.logger.lifecycle('')
                project.logger.lifecycle('Полная сборка завершена:')
                project.logger.lifecycle("  Java 8:  " + java8Jar.get().archiveFile.get().asFile)
                project.logger.lifecycle("  Java 25: " + java25Jar.get().archiveFile.get().asFile)
            }
        }
    }
}