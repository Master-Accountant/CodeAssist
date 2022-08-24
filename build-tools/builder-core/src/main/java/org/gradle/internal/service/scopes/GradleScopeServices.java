package org.gradle.internal.service.scopes;

import org.gradle.api.internal.BuildScopeListenerRegistrationListener;
import org.gradle.api.internal.CollectionCallbackActionDecorator;
import org.gradle.api.internal.artifacts.dsl.dependencies.ProjectFinder;
import org.gradle.api.internal.collections.DomainObjectCollectionFactory;
import org.gradle.api.internal.plugins.DefaultPluginManager;
import org.gradle.api.internal.plugins.ImperativeOnlyPluginTarget;
import org.gradle.api.internal.plugins.PluginManagerInternal;
import org.gradle.api.internal.plugins.PluginRegistry;
import org.gradle.api.internal.plugins.PluginTarget;
import org.gradle.api.internal.project.ProjectStateRegistry;
import org.gradle.api.services.internal.BuildServiceRegistryInternal;
import org.gradle.api.services.internal.DefaultBuildServicesRegistry;
import org.gradle.cache.GlobalCacheLocations;
import org.gradle.cache.internal.DefaultFileContentCacheFactory;
import org.gradle.cache.internal.FileContentCacheFactory;
import org.gradle.cache.internal.InMemoryCacheDecoratorFactory;
import org.gradle.cache.internal.SplitFileContentCacheFactory;
import org.gradle.cache.scopes.BuildScopedCache;
import org.gradle.configuration.ConfigurationTargetIdentifier;
import org.gradle.configuration.internal.ListenerBuildOperationDecorator;
import org.gradle.configuration.internal.UserCodeApplicationContext;
import org.gradle.execution.BuildWorkExecutor;
import org.gradle.execution.ProjectConfigurer;
import org.gradle.api.execution.TaskExecutionGraphListener;
import org.gradle.api.execution.TaskExecutionListener;
import org.gradle.execution.TaskSelector;
import org.gradle.execution.plan.ExecutionNodeAccessHierarchies;
import org.gradle.execution.plan.LocalTaskNodeExecutor;
import org.gradle.execution.plan.NodeExecutor;
import org.gradle.execution.plan.PlanExecutor;
import org.gradle.execution.plan.WorkNodeExecutor;
import org.gradle.internal.Factory;
import org.gradle.api.internal.GradleInternal;
import org.gradle.internal.build.BuildState;
import org.gradle.internal.concurrent.CompositeStoppable;
import org.gradle.internal.event.ListenerBroadcast;
import org.gradle.internal.event.ListenerManager;
import org.gradle.internal.execution.BuildOutputCleanupRegistry;
import org.gradle.execution.taskgraph.DefaultTaskExecutionGraph;
import org.gradle.execution.taskgraph.TaskExecutionGraphInternal;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.internal.id.UniqueId;
import org.gradle.internal.instantiation.InstantiatorFactory;
import org.gradle.internal.isolation.IsolatableFactory;
import org.gradle.internal.logging.text.StyledTextOutputFactory;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.resources.SharedResourceLeaseRegistry;
import org.gradle.internal.service.DefaultServiceRegistry;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.api.internal.tasks.options.OptionReader;
import org.gradle.internal.scopeids.id.BuildInvocationScopeId;
import org.gradle.internal.vfs.FileSystemAccess;
import org.gradle.internal.work.WorkerLeaseService;
import org.gradle.internal.work.AsyncWorkTracker;
import org.gradle.internal.work.DefaultAsyncWorkTracker;
import org.gradle.configuration.project.BuiltInCommand;
import org.gradle.execution.BuildConfigurationAction;
import org.gradle.execution.BuildConfigurationActionExecuter;
import org.gradle.execution.BuildOperationFiringBuildWorkerExecutor;
import org.gradle.execution.DefaultBuildConfigurationActionExecuter;
import org.gradle.execution.DefaultTasksBuildExecutionAction;
import org.gradle.execution.DryRunBuildExecutionAction;
import org.gradle.execution.SelectedTaskExecutionAction;
import org.gradle.execution.TaskNameResolvingBuildConfigurationAction;
import org.gradle.execution.commandline.CommandLineTaskConfigurer;
import org.gradle.execution.commandline.CommandLineTaskParser;
import org.gradle.initialization.DefaultTaskExecutionPreparer;
import org.gradle.initialization.TaskExecutionPreparer;
import org.gradle.internal.buildtree.BuildModelParameters;
import org.gradle.internal.cleanup.DefaultBuildOutputCleanupRegistry;
import org.gradle.execution.taskgraph.TaskListenerInternal;
import org.gradle.internal.logging.LoggingManagerInternal;

import java.util.LinkedList;
import java.util.List;

public class GradleScopeServices extends DefaultServiceRegistry {
    private final CompositeStoppable registries = new CompositeStoppable();

    public GradleScopeServices(final ServiceRegistry parent) {
        super(parent);
        register(registration -> {
            for (PluginServiceRegistry pluginServiceRegistry : parent.getAll(PluginServiceRegistry.class)) {
                pluginServiceRegistry.registerGradleServices(registration);
            }
        });
    }

    BuildServiceRegistryInternal createSharedServiceRegistry(
            BuildState buildState,
            Instantiator instantiator,
            DomainObjectCollectionFactory factory,
            InstantiatorFactory instantiatorFactory,
            ServiceRegistry services,
            ListenerManager listenerManager,
            IsolatableFactory isolatableFactory,
            SharedResourceLeaseRegistry sharedResourceLeaseRegistry
    ) {
        return instantiator.newInstance(
                DefaultBuildServicesRegistry.class,
                buildState.getBuildIdentifier(),
                factory,
                instantiatorFactory,
                services,
                listenerManager,
                isolatableFactory,
                sharedResourceLeaseRegistry
        );
    }

    BuildOutputCleanupRegistry createBuildOutputCleanupRegistry(
            FileCollectionFactory factory
    ) {
        return new DefaultBuildOutputCleanupRegistry(factory);
    }

    AsyncWorkTracker createAsyncWorkTracker(
            WorkerLeaseService workerLeaseService
    ) {
        return new DefaultAsyncWorkTracker(workerLeaseService);
    }

    LocalTaskNodeExecutor createLocalTaskNodeExecutor(ExecutionNodeAccessHierarchies executionNodeAccessHierarchies) {
        return new LocalTaskNodeExecutor(
                executionNodeAccessHierarchies.getOutputHierarchy()
        );
    }

    WorkNodeExecutor createWorkNodeExecutor() {
        return new WorkNodeExecutor();
    }

    ListenerBroadcast<TaskExecutionGraphListener> createTaskExecutionGraphListenerBroadcast(
            ListenerManager listenerManager
    ) {
        return listenerManager.createAnonymousBroadcaster(TaskExecutionGraphListener.class);
    }

    ListenerBroadcast<TaskExecutionListener> createTaskExecutionListenerBroadcast(
            ListenerManager listenerManager
    ) {
        return listenerManager.createAnonymousBroadcaster(TaskExecutionListener.class);
    }

    TaskExecutionGraphInternal createTaskExecutionGraph(
            PlanExecutor planExecutor,
            List<NodeExecutor> nodeExecutors,
            BuildOperationExecutor buildOperationExecutor,
            ListenerBuildOperationDecorator listenerBuildOperationDecorator,
            GradleInternal gradleInternal,
            ListenerBroadcast<TaskExecutionListener> taskListeners,
            ListenerBroadcast<TaskExecutionGraphListener> graphListeners,
            ListenerManager listenerManager,
            ProjectStateRegistry projectStateRegistry,
            ServiceRegistry gradleScopedServices
    ) {
        return new DefaultTaskExecutionGraph(
                planExecutor,
                nodeExecutors,
                buildOperationExecutor,
                listenerBuildOperationDecorator,
                gradleInternal,
                graphListeners,
                taskListeners,
                listenerManager.getBroadcaster(BuildScopeListenerRegistrationListener.class),
                projectStateRegistry,
                gradleScopedServices
        );
    }

    PluginRegistry createPluginRegistry(PluginRegistry parentRegistry) {
        return parentRegistry.createChild(get(GradleInternal.class).getClassLoaderScope());
    }

    PluginManagerInternal createPluginManager(Instantiator instantiator, GradleInternal gradleInternal, PluginRegistry pluginRegistry, InstantiatorFactory instantiatorFactory, BuildOperationExecutor buildOperationExecutor, UserCodeApplicationContext userCodeApplicationContext, CollectionCallbackActionDecorator decorator, DomainObjectCollectionFactory domainObjectCollectionFactory) {
        PluginTarget target = new ImperativeOnlyPluginTarget<GradleInternal>(gradleInternal);
        return instantiator.newInstance(DefaultPluginManager.class, pluginRegistry, instantiatorFactory.inject(this), target, buildOperationExecutor, userCodeApplicationContext, decorator, domainObjectCollectionFactory);
    }

    FileContentCacheFactory createFileContentCacheFactory(
            GlobalCacheLocations globalCacheLocations,
            BuildScopedCache cacheRepository,
            FileContentCacheFactory globalCacheFactory,
            InMemoryCacheDecoratorFactory inMemoryCacheDecoratorFactory,
            ListenerManager listenerManager,
            FileSystemAccess fileSystemAccess
    ) {
        DefaultFileContentCacheFactory localCacheFactory = new DefaultFileContentCacheFactory(
                listenerManager,
                fileSystemAccess,
                cacheRepository,
                inMemoryCacheDecoratorFactory
        );
        return new SplitFileContentCacheFactory(
                globalCacheFactory,
                localCacheFactory,
                globalCacheLocations
        );
    }

    ServiceRegistryFactory createServiceRegistryFactory(final ServiceRegistry services) {
        final Factory<LoggingManagerInternal> loggingManagerInternalFactory = getFactory(LoggingManagerInternal.class);
        return new ServiceRegistryFactory() {
            @Override
            public ServiceRegistry createFor(Object domainObject) {
                if (domainObject instanceof ProjectInternal) {
                    ProjectScopeServices projectScopeServices = new ProjectScopeServices(services, (ProjectInternal) domainObject, loggingManagerInternalFactory);
                    registries.add(projectScopeServices);
                    return projectScopeServices;
                }
                throw new UnsupportedOperationException();
            }
        };
    }

    BuildConfigurationActionExecuter createBuildConfigurationActionExecuter(CommandLineTaskParser commandLineTaskParser, ProjectConfigurer projectConfigurer, ProjectStateRegistry projectStateRegistry, List<BuiltInCommand> builtInCommands) {
        List<BuildConfigurationAction> taskSelectionActions = new LinkedList<>();
        taskSelectionActions.add(new DefaultTasksBuildExecutionAction(projectConfigurer, builtInCommands));
        taskSelectionActions.add(new TaskNameResolvingBuildConfigurationAction(commandLineTaskParser));
        return new DefaultBuildConfigurationActionExecuter(taskSelectionActions, projectStateRegistry);
    }

    TaskExecutionPreparer createTaskExecutionPreparer(BuildConfigurationActionExecuter buildConfigurationActionExecuter, BuildOperationExecutor buildOperationExecutor, BuildModelParameters buildModelParameters) {
        return new DefaultTaskExecutionPreparer(buildConfigurationActionExecuter, buildOperationExecutor, buildModelParameters);
    }

    ProjectFinder createProjectFinder(final GradleInternal gradle) {
        return new DefaultProjectFinder(gradle::getRootProject);
    }


    OptionReader createOptionReader() {
        return new OptionReader();
    }

    CommandLineTaskParser createCommandLineTaskParser(OptionReader optionReader, TaskSelector taskSelector) {
        return new CommandLineTaskParser(new CommandLineTaskConfigurer(optionReader), taskSelector);
    }

    BuildWorkExecutor createBuildExecuter(StyledTextOutputFactory textOutputFactory, BuildOperationExecutor buildOperationExecutor) {
        return new BuildOperationFiringBuildWorkerExecutor(
                new DryRunBuildExecutionAction(textOutputFactory,
                        new SelectedTaskExecutionAction()),
                buildOperationExecutor);
    }

    TaskExecutionListener createTaskExecutionListener(ListenerBroadcast<TaskExecutionListener> broadcast) {
        return broadcast.getSource();
    }

    TaskListenerInternal createTaskListenerInternal(ListenerManager listenerManager) {
        return listenerManager.getBroadcaster(TaskListenerInternal.class);
    }

    protected ConfigurationTargetIdentifier createConfigurationTargetIdentifier(GradleInternal gradle) {
        return ConfigurationTargetIdentifier.of(gradle);
    }

    // This needs to go here instead of being “build tree” scoped due to the GradleBuild task.
    // Builds launched by that task are part of the same build tree, but should have their own invocation ID.
    // Such builds also have their own root Gradle object.
    protected BuildInvocationScopeId createBuildInvocationScopeId(GradleInternal gradle) {
        GradleInternal rootGradle = gradle.getRoot();
        if (gradle == rootGradle) {
            return new BuildInvocationScopeId(UniqueId.generate());
        } else {
            return rootGradle.getServices().get(BuildInvocationScopeId.class);
        }
    }

    @Override
    public void close() {
        registries.stop();
        super.close();
    }
}