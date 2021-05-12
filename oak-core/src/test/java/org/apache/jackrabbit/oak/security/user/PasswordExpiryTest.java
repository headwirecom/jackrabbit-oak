/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.security.user;

import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.jackrabbit.oak.AbstractSecurityTest;
import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Tree;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.nodetype.ReadOnlyNodeTypeManager;
import org.apache.jackrabbit.oak.plugins.tree.TreeUtil;
import org.apache.jackrabbit.oak.spi.nodetype.NodeTypeConstants;
import org.apache.jackrabbit.oak.spi.security.ConfigurationParameters;
import org.apache.jackrabbit.oak.spi.security.authentication.Authentication;
import org.apache.jackrabbit.oak.spi.security.user.UserConfiguration;
import org.apache.jackrabbit.oak.spi.security.user.UserConstants;
import org.jetbrains.annotations.NotNull;
import org.junit.Before;
import org.junit.Test;

import javax.jcr.RepositoryException;
import javax.jcr.SimpleCredentials;
import javax.security.auth.login.CredentialExpiredException;
import javax.security.auth.login.LoginException;
import java.util.UUID;

import static org.apache.jackrabbit.oak.spi.security.user.UserConstants.REP_PASSWORD_LAST_MODIFIED;
import static org.apache.jackrabbit.oak.spi.security.user.UserConstants.REP_PWD;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @see <a href="https://issues.apache.org/jira/browse/OAK-1922">OAK-1922</a>
 */
public class PasswordExpiryTest extends AbstractSecurityTest {

    private String userId;

    @Before
    public void before() throws Exception {
        super.before();
        userId = getTestUser().getID();
    }

    @Override
    protected ConfigurationParameters getSecurityConfigParameters() {
        ConfigurationParameters userConfig = ConfigurationParameters.of(UserConstants.PARAM_PASSWORD_MAX_AGE, 10);
        return ConfigurationParameters.of(UserConfiguration.NAME, userConfig);
    }

    @NotNull
    private Tree getUserTree(@NotNull User user) throws RepositoryException {
        return root.getTree(user.getPath());
    }

    @Test
    public void testCreateUser() throws Exception {
        String newUserId = "newuser" + UUID.randomUUID();
        User user = null;

        try {
            user = getUserManager(root).createUser(newUserId, newUserId);
            root.commit();

            Tree pwdTree = getUserTree(user).getChild(UserConstants.REP_PWD);
            assertTrue(pwdTree.exists());
            assertTrue(TreeUtil.isNodeType(pwdTree, UserConstants.NT_REP_PASSWORD, root.getTree(NodeTypeConstants.NODE_TYPES_PATH)));

            ReadOnlyNodeTypeManager ntMgr = ReadOnlyNodeTypeManager.getInstance(root, getNamePathMapper());
            assertTrue(ntMgr.getDefinition(pwdTree.getParent(), pwdTree).isProtected());

            PropertyState property = pwdTree.getProperty(UserConstants.REP_PASSWORD_LAST_MODIFIED);
            assertNotNull(property);
            assertEquals(Type.LONG, property.getType());
            assertTrue(property.getValue(Type.LONG, 0) > 0);

            // protected properties must not be exposed by User#hasProperty
            assertFalse(user.hasProperty(UserConstants.REP_PWD + "/" + UserConstants.REP_PASSWORD_LAST_MODIFIED));
        } finally {
            if (user != null) {
                user.remove();
                root.commit();
            }
        }
    }

    @Test
    public void testChangePassword() throws Exception {
        User user = getTestUser();
        PropertyState p1 = getUserTree(user).getChild(UserConstants.REP_PWD).getProperty(UserConstants.REP_PASSWORD_LAST_MODIFIED);
        long oldModTime = p1.getValue(Type.LONG, 0);
        assertTrue(oldModTime > 0);
        waitForSystemTimeIncrement(oldModTime);
        user.changePassword(userId);
        root.commit();
        PropertyState p2 = getUserTree(user).getChild(UserConstants.REP_PWD).getProperty(UserConstants.REP_PASSWORD_LAST_MODIFIED);
        long newModTime = p2.getValue(Type.LONG, 0);
        assertTrue(newModTime > oldModTime);
    }

    @Test
    public void testAuthenticatePasswordExpiredNewUser() throws Exception {
        Authentication a = new UserAuthentication(getUserConfiguration(), root, userId);
        // during user creation pw last modified is set, thus it shouldn't expire
        assertTrue(a.authenticate(new SimpleCredentials(userId, userId.toCharArray())));
    }

    @Test
    public void testAuthenticatePasswordExpired() throws Exception {
        Authentication a = new UserAuthentication(getUserConfiguration(), root, userId);
        // set password last modified to beginning of epoch
        getUserTree(getTestUser()).getChild(UserConstants.REP_PWD).setProperty(UserConstants.REP_PASSWORD_LAST_MODIFIED, 0);
        root.commit();
        try {
            a.authenticate(new SimpleCredentials(userId, userId.toCharArray()));
            fail("Credentials should be expired");
        } catch (CredentialExpiredException e) {
            // success
        }
    }

    @Test
    public void testAuthenticateBeforePasswordExpired() throws Exception {
        Authentication a = new UserAuthentication(getUserConfiguration(), root, userId);
        // set password last modified to beginning of epoch
        getUserTree(getTestUser()).getChild(UserConstants.REP_PWD).setProperty(UserConstants.REP_PASSWORD_LAST_MODIFIED, 0);
        root.commit();
        try {
            a.authenticate(new SimpleCredentials(userId, "wrong".toCharArray()));
        } catch (CredentialExpiredException e) {
            fail("Login should fail before expiry");
        } catch (LoginException e) {
            // success - userId/pw mismatch takes precedence over expiry
        }
    }

    @Test
    public void testAuthenticatePasswordExpiredChangePassword() throws Exception {
        Authentication a = new UserAuthentication(getUserConfiguration(), root, userId);
        // set password last modified to beginning of epoch
        getUserTree(getTestUser()).getChild(UserConstants.REP_PWD).setProperty(UserConstants.REP_PASSWORD_LAST_MODIFIED, 0);
        root.commit();

        // changing the password should reset the pw last mod and the pw no longer be expired
        getTestUser().changePassword(userId);
        root.commit();
        assertTrue(a.authenticate(new SimpleCredentials(userId, userId.toCharArray())));
    }

    @Test
    public void testByDefaultAdminHasNoPwNode() throws Exception {
        User adminUser = getUserManager(root).getAuthorizable(getUserConfiguration().getParameters().getConfigValue(UserConstants.PARAM_ADMIN_ID, UserConstants.DEFAULT_ADMIN_ID), User.class);
        assertFalse(root.getTree(adminUser.getPath()).getChild(UserConstants.REP_PWD).exists());
    }

    /**
     * import existing user: rep:passwordLastModified must not be updated (unless this property is explicitly included in the xml import)
     */
    @Test
    public void testSetPasswordImportExistingUser() throws Exception {
        UserManager userManager = getUserManager(root);
        if (userManager instanceof UserManagerImpl) {
            Tree userTree = getUserTree(getTestUser());
            assertTrue(userTree.hasChild(REP_PWD));
            PropertyState lastMod = userTree.getChild(REP_PWD).getProperty(REP_PASSWORD_LAST_MODIFIED);

            waitForSystemTimeIncrement(lastMod.getValue(Type.LONG));

            ((UserManagerImpl) userManager).setPassword(userTree, getTestUser().getID(), "pwd", true);
            assertTrue(userTree.hasChild(REP_PWD));
            assertEquals(lastMod.getValue(Type.LONG), userTree.getChild(REP_PWD).getProperty(REP_PASSWORD_LAST_MODIFIED).getValue(Type.LONG));
        }
    }

    /**
     * import new user: rep:passwordLastModified must be created as it would when calling UserManager.createUser
     */
    @Test
    public void testSetPasswordImportNewUser() throws Exception {
        UserManager userManager = getUserManager(root);
        if (userManager instanceof UserManagerImpl) {
            User u = userManager.createUser("uNew", null);
            Tree userTree = getUserTree(u);
            assertFalse(userTree.hasChild(REP_PWD));

            ((UserManagerImpl) userManager).setPassword(userTree, "uNew", "pwd", true);
            assertTrue(userTree.hasChild(REP_PWD));
            assertTrue(userTree.getChild(REP_PWD).hasProperty(REP_PASSWORD_LAST_MODIFIED));
        }
    }
}
