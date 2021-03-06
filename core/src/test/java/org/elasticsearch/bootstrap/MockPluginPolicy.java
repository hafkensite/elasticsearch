/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.bootstrap;

import com.carrotsearch.randomizedtesting.RandomizedRunner;

import org.apache.lucene.util.LuceneTestCase;
import org.elasticsearch.common.logging.Loggers;
import org.junit.Assert;

import java.net.URL;
import java.security.CodeSource;
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.Policy;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Simulates in unit tests per-plugin permissions.
 * Unit tests for plugins do not have a proper plugin structure,
 * so we don't know which codebases to apply the permission to.
 * <p>
 * As an approximation, we just exclude es/test/framework classes,
 * because they will be present in stacks and fail tests for the 
 * simple case where an AccessController block is missing, because
 * java security checks every codebase in the stacktrace, and we
 * are sure to pollute it.
 */
final class MockPluginPolicy extends Policy {
    final ESPolicy standardPolicy;
    final PermissionCollection extraPermissions;
    final Set<CodeSource> excludedSources;

    /**
     * Create a new MockPluginPolicy with dynamic {@code permissions} and
     * adding the extra plugin permissions from {@code insecurePluginProp} to
     * all code except test classes.
     */
    MockPluginPolicy(Permissions permissions, String insecurePluginProp) throws Exception {
        // the hack begins!

        // parse whole policy file, with and without the substitution, compute the delta
        standardPolicy = new ESPolicy(permissions);

        URL bogus = new URL("file:/bogus"); // its "any old codebase" this time: generic permissions
        PermissionCollection smallPermissions = standardPolicy.template.getPermissions(new CodeSource(bogus, (Certificate[])null)); 
        Set<Permission> small = new HashSet<>(Collections.list(smallPermissions.elements()));

        // set the URL for the property substitution, this time it will also have special permissions
        System.setProperty(insecurePluginProp, bogus.toString());
        ESPolicy biggerPolicy = new ESPolicy(permissions);
        System.clearProperty(insecurePluginProp);
        PermissionCollection bigPermissions = biggerPolicy.template.getPermissions(new CodeSource(bogus, (Certificate[])null));
        Set<Permission> big = new HashSet<>(Collections.list(bigPermissions.elements()));

        // compute delta to remove all the generic permissions
        // we want equals() vs implies() for this check, in case we need 
        // to pass along any UnresolvedPermission to the plugin
        big.removeAll(small);

        // build collection of the special permissions for easy checking
        extraPermissions = new Permissions();
        for (Permission p : big) {
            extraPermissions.add(p);
        }

        excludedSources = new HashSet<CodeSource>();
        // exclude some obvious places
        // es core
        excludedSources.add(Bootstrap.class.getProtectionDomain().getCodeSource());
        // es test framework
        excludedSources.add(getClass().getProtectionDomain().getCodeSource());
        // lucene test framework
        excludedSources.add(LuceneTestCase.class.getProtectionDomain().getCodeSource());
        // test runner
        excludedSources.add(RandomizedRunner.class.getProtectionDomain().getCodeSource());
        // junit library
        excludedSources.add(Assert.class.getProtectionDomain().getCodeSource());
        // groovy scripts
        excludedSources.add(new CodeSource(new URL("file:/groovy/script"), (Certificate[])null));

        Loggers.getLogger(getClass()).debug("Apply permissions [{}] excluding codebases [{}]", extraPermissions, excludedSources);
    }

    @Override
    public boolean implies(ProtectionDomain domain, Permission permission) {
        if (standardPolicy.implies(domain, permission)) {
            return true;
        } else if (excludedSources.contains(domain.getCodeSource()) == false && 
                   Objects.toString(domain.getCodeSource()).contains("test-classes") == false) {
            return extraPermissions.implies(permission);
        } else {
            return false;
        }
    }
}
