/**
 * Copyright 2015-2016 Red Hat, Inc, and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wildfly.swarm.plugin.maven;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.Authentication;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.impl.ArtifactResolver;
import org.eclipse.aether.repository.LocalArtifactRequest;
import org.eclipse.aether.repository.LocalArtifactResult;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.util.graph.visitor.PreorderNodeListGenerator;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.wildfly.swarm.tools.ArtifactResolvingHelper;
import org.wildfly.swarm.tools.ArtifactSpec;

/**
 * @author Bob McWhirter
 */
public class MavenArtifactResolvingHelper implements ArtifactResolvingHelper {


    final protected RepositorySystemSession session;

    final protected List<RemoteRepository> remoteRepositories = new ArrayList<>();

    final private ArtifactResolver resolver;

    final private RepositorySystem system;

    public MavenArtifactResolvingHelper(ArtifactResolver resolver,
                                        RepositorySystem system,
                                        RepositorySystemSession session) {
        this.resolver = resolver;
        this.system = system;
        this.session = session;
        this.remoteRepositories.add(new RemoteRepository.Builder("jboss-public-repository-group", "default", "http://repository.jboss.org/nexus/content/groups/public/").build());
    }

    public void remoteRepository(ArtifactRepository repo) {
        RemoteRepository.Builder builder = new RemoteRepository.Builder(repo.getId(), "default", repo.getUrl());
        final Authentication mavenAuth = repo.getAuthentication();
        if (mavenAuth != null && mavenAuth.getUsername() != null && mavenAuth.getPassword() != null) {
            builder.setAuthentication(new AuthenticationBuilder()
                    .addUsername(mavenAuth.getUsername())
                    .addPassword(mavenAuth.getPassword()).build());
        }
        this.remoteRepositories.add(builder.build());
    }

    public void remoteRepository(RemoteRepository repo) {
        this.remoteRepositories.add(repo);
    }

    @Override
    public ArtifactSpec resolve(ArtifactSpec spec) {
        if (spec.file == null) {
            final DefaultArtifact artifact = new DefaultArtifact(spec.groupId(), spec.artifactId(), spec.classifier(),
                    spec.type(), spec.version());

            final LocalArtifactResult localResult = this.session.getLocalRepositoryManager()
                    .find(this.session, new LocalArtifactRequest(artifact, this.remoteRepositories, null));
            if (localResult.isAvailable()) {
                spec.file = localResult.getFile();
            } else {
                try {
                    final ArtifactResult result = resolver.resolveArtifact(this.session,
                            new ArtifactRequest(artifact,
                                    this.remoteRepositories,
                                    null));
                    if (result.isResolved()) {
                        spec.file = result.getArtifact().getFile();
                    }
                } catch (ArtifactResolutionException e) {
                    System.err.println("ERR " + e);
                    e.printStackTrace();
                }
            }
        }

        return spec.file != null ? spec : null;

    }

    @Override
    public Set<ArtifactSpec> resolveAll(Set<ArtifactSpec> specs) throws Exception {
        if (specs.isEmpty()) {

            return specs;
        }

        final CollectRequest request = new CollectRequest();
        request.setRepositories(this.remoteRepositories);

        specs.forEach(spec -> request
                .addDependency(new Dependency(new DefaultArtifact(spec.groupId(),
                        spec.artifactId(),
                        spec.classifier(),
                        spec.type(),
                        spec.version()),
                        "compile")));

        CollectResult result = this.system.collectDependencies(this.session, request);

        PreorderNodeListGenerator gen = new PreorderNodeListGenerator();
        result.getRoot().accept(gen);

        return gen.getNodes().stream()
                .filter(node -> !"system".equals(node.getDependency().getScope()))
                .map(node -> {
                    final Artifact artifact = node.getArtifact();

                    return new ArtifactSpec(node.getDependency().getScope(),
                            artifact.getGroupId(),
                            artifact.getArtifactId(),
                            artifact.getVersion(),
                            artifact.getExtension(),
                            artifact.getClassifier(),
                            null);
                })
                .map(this::resolve)
                .filter(x -> x != null)
                .collect(Collectors.toSet());
    }

}
