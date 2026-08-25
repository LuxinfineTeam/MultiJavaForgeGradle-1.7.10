package team.luxinfine.gradle

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

        def useLocalSplitter = checkLocalSplitter(project)

        configureJava(project)
        configureJars(project, useLocalSplitter)

        if (useLocalSplitter) {
            configureSplitter(project)
        } else {
            configurePublicSplitterPlugin(project)
        }

        project.afterEvaluate {
            configureManifest(project, useLocalSplitter)
            configureExcludes(project, useLocalSplitter)
            configureReobf(project, useLocalSplitter)
            if (useLocalSplitter) {
                configureSplitterTasks(project)
                configureProfileTasks(project)
                configureMultiBuild(project, true)
            } else {
                hidePublicPluginTasks(project)
                configurePublicSplitterTasks(project)
                configureMultiBuild(project, false)
            }
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

    private static void configureJars(Project project, boolean createJava25) {
        project.tasks.named('jar', Jar) {
            archiveClassifier = 'j8'
        }

        if (createJava25) {
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
    }

    private static void configureManifest(Project project, boolean includeJava25) {
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

        if (includeJava25) {
            project.tasks.named('jarJava25', Jar) {
                manifest {
                    attributes manifestAttributes
                }
            }
        }
    }

    private static void configureExcludes(Project project, boolean includeJava25) {
        if (!project.hasProperty('jarExcludes'))
            return

        def jarExcludes = project.ext.jarExcludes

        if (jarExcludes instanceof List && jarExcludes.isEmpty())
            return

        project.tasks.named('jar', Jar) {
            if (jarExcludes instanceof List) {
                jarExcludes.each { pattern ->
                    exclude pattern
                }
            } else {
                exclude jarExcludes
            }
        }

        if (includeJava25) {
            project.tasks.named('jarJava25', Jar) {
                if (jarExcludes instanceof List) {
                    jarExcludes.each { pattern ->
                        exclude pattern
                    }
                } else {
                    exclude jarExcludes
                }
            }
        }
    }

    private static boolean checkLocalSplitter(Project project) {
        def splitterRepo = Paths.get(System.getProperty('user.home'), 'IDEA Projects', 'JarSplitter').toAbsolutePath()
        def splitterJar = splitterRepo.resolve('Main/JarSplitter-3.0.jar')

        if (Files.exists(splitterJar)) {
            project.logger.lifecycle('=== Local JarSplitter found ===')
            project.logger.lifecycle("Path: ${splitterJar}")
            return true
        } else {
            project.logger.lifecycle('=== Local JarSplitter not found ===')
            project.logger.lifecycle("Expected path: ${splitterJar}")
            project.logger.lifecycle('Using public JarSplitter plugin from JitPack')
            return false
        }
    }

    private static void configurePublicSplitterPlugin(Project project) {
        try {
            project.pluginManager.apply('team.luxinfine.jarsplitter')
            project.logger.lifecycle('Public JarSplitter plugin applied successfully')
        } catch (Exception e) {
            project.logger.warn("Error: ${e.message}")
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

                        project.file('gradle.properties').withInputStream {properties.load(it)}
                        properties.propertyNames().each { propertiesList.add(it.toString() + '=' + properties.getProperty(it.toString())) }

                        // Добавляем профиль если он установлен
                        if (project.ext.has('Profile') && project.ext.Profile) {
                            propertiesList.add('Modules.ModuleBuildSystem.Profile=' + project.ext.Profile)
                        }

                        propertiesList.add('BuildPath=' + builtJar.absolutePath)
                        propertiesList.add('SourcesDir=' + project.sourceSets.main.java.srcDirs[0])
                        propertiesList.add('DependenciesPaths=' + depends.stream().filter {it.toString().endsWith('.jar')}.collect(Collectors.joining(';')))
                        propertiesList.add('MCMappingsPath=' + mappingsFile)

                        Files.write(depsFile, propertiesList)

                        args = [depsFile.toString()]

                        def profileInfo = (project.ext.has('Profile') && project.ext.Profile) ? " with profile ${project.ext.Profile}" : ''

                        project.logger.lifecycle('')
                        project.logger.lifecycle('========================================')
                        project.logger.lifecycle("Running JarSplitter${profileInfo}")
                        project.logger.lifecycle("Input: ${builtJar}")
                        project.logger.lifecycle('========================================')
                    }
                }
        }
    }

    private static void configureReobf(Project project, boolean includeJava25) {
        def reobfTask = project.tasks.getByName('reobf')

        project.logger.lifecycle('=== Adding jars to reobf ===')

        // ForgeGradle automatically adds 'jar' task to reobf, so we don't add it manually
        // Only add jarJava25 if needed

        if (includeJava25) {
            def java25Jar = project.tasks.getByName('jarJava25')
            reobfTask.reobf(java25Jar) { artifactSpec ->
                artifactSpec.setClasspath(project.sourceSets.main.compileClasspath)
            }
        }

        project.logger.lifecycle("Reobf artifacts count: " + reobfTask.getObfuscated().size())

        reobfTask.getObfuscated().each { artifact ->
            project.logger.lifecycle("  Input: ${artifact.getToObf()}")
            project.logger.lifecycle("  Output: ${artifact.getFile()}")
            project.logger.lifecycle("  Classpath: " + (artifact.classpath != null ? artifact.classpath.files.size() + ' files' : 'null'))
        }
    }

    private static void hidePublicPluginTasks(Project project) {
        // Hide public plugin tasks as they are confusing when only j8 is supported
        ['buildAll', 'buildClient', 'buildServer', 'buildDev'].each { taskName ->
            try {
                project.tasks.named(taskName).configure {
                    group = null  // Remove from task list
                }
            } catch (Exception e) {
                // Task doesn't exist, ignore
            }
        }
        project.logger.lifecycle('Public JarSplitter plugin tasks hidden (only j8 build supported without local splitter)')
    }

    private static void configurePublicSplitterTasks(Project project) {
        def java8Jar = project.tasks.getByName('jar')

        // Only create task for Java 8 - public plugin doesn't support multiple jars
        def useSplitterJ8 = project.tasks.register('useSplitterJ8') {
            group = 'build'
            description = 'Runs public JarSplitter plugin buildAll for Java 8 jar'

            dependsOn project.tasks.named('reobf')

            doFirst {
                project.logger.lifecycle('')
                project.logger.lifecycle('========================================')
                project.logger.lifecycle('Running public JarSplitter for Java 8')
                project.logger.lifecycle("Input: ${java8Jar.archiveFile.get().asFile}")
                project.logger.lifecycle('========================================')
            }
        }

        // Add dependency on buildAll for j8
        try {
            def buildAllTask = project.tasks.named('buildAll')
            useSplitterJ8.configure { dependsOn buildAllTask }
            project.logger.lifecycle('JarSplitter buildAll task configured for Java 8')
        } catch (Exception e) {
            project.logger.warn("buildAll task not found: ${e.message}")
        }

        project.logger.lifecycle('NOTE: Java 25 build requires local JarSplitter')
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

    private static void configureProfileTasks(Project project) {
        def profilesDir = project.file('BuildProfiles')

        if (!profilesDir.exists() || !profilesDir.isDirectory()) {
            project.logger.lifecycle('BuildProfiles directory not found, skipping profile tasks')
            return
        }

        // Инициализация свойства Profile
        if (!project.ext.has('Profile')) {
            project.ext.set('Profile', '')
        }

        def profileFiles = profilesDir.listFiles({ f -> f.name.endsWith('.yml') } as FileFilter)

        if (profileFiles == null || profileFiles.length == 0) {
            project.logger.lifecycle('No profile files found in BuildProfiles')
            return
        }

        profileFiles.each { profileFile ->
            def profileName = profileFile.name.replace('.yml', '')
            def taskName = profileName.capitalize()
            def profilePath = "BuildProfiles/${profileFile.name}"

            // Создаем задачу профиля, которая запускает multiBuild с установленным профилем
            project.tasks.register(taskName) {
                group = 'build profiles'
                description = "Build with profile ${profileFile.name} (Java 8 + Java 25)"

                // Устанавливаем профиль в configure, чтобы он был доступен до выполнения зависимостей
                project.gradle.taskGraph.whenReady { taskGraph ->
                    if (taskGraph.hasTask(":${taskName}")) {
                        project.ext.set('Profile', profilePath)
                        project.logger.lifecycle('')
                        project.logger.lifecycle('========================================')
                        project.logger.lifecycle("Building with profile: ${profilePath}")
                        project.logger.lifecycle('========================================')
                    }
                }

                dependsOn 'multiBuild'
            }

            project.logger.lifecycle("Registered profile task: ${taskName}")
        }
    }

    private static void configureMultiBuild(Project project, boolean useLocalSplitter) {
        project.tasks.register('multiBuild') {
            group = 'build'

            if (useLocalSplitter) {
                dependsOn 'useSplitterJ8'
                dependsOn 'useSplitterJ25'
                description = 'Builds Java 8 and Java 25 versions, reobfs them and runs JarSplitter'
            } else {
                dependsOn 'useSplitterJ8'
                description = 'Builds Java 8 version, reobfs it and runs JarSplitter (Java 25 requires local JarSplitter)'
            }

            doLast {
                def java8Jar = project.tasks.named('jar')
                project.logger.lifecycle('')
                project.logger.lifecycle('========================================')
                project.logger.lifecycle('Полная сборка завершена:')
                project.logger.lifecycle("  Java 8:  " + java8Jar.get().archiveFile.get().asFile)

                if (useLocalSplitter) {
                    def java25Jar = project.tasks.named('jarJava25')
                    project.logger.lifecycle("  Java 25: " + java25Jar.get().archiveFile.get().asFile)
                    project.logger.lifecycle("  Splitter: Local JarSplitter")
                } else {
                    project.logger.lifecycle("  Java 25: SKIPPED (requires local JarSplitter)")
                    project.logger.lifecycle("  Splitter: Public plugin from JitPack (j8 only)")
                }
                project.logger.lifecycle('========================================')
            }
        }
    }
}