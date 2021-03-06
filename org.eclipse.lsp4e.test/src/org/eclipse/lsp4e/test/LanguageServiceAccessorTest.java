/*******************************************************************************
 * Copyright (c) 2016-2017 Rogue Wave Software Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *  Michał Niewrzał (Rogue Wave Software Inc.) - initial implementation
 *  Mickael Istria (Red Hat Inc.) - added test for Run config
 *******************************************************************************/
package org.eclipse.lsp4e.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.util.Collection;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.lsp4e.LanguageServiceAccessor;
import org.eclipse.lsp4e.LanguageServiceAccessor.LSPDocumentInfo;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.ui.PlatformUI;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class LanguageServiceAccessorTest {

	private IProject project;

	@Before
	public void setUp() throws CoreException {
		project = TestUtils.createProject("LanguageServiceAccessorTest" + System.currentTimeMillis());
	}

	@After
	public void tearDown() throws CoreException {
		PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().closeAllEditors(false);
		project.delete(true, true, new NullProgressMonitor());
	}

	@Test
	public void testGetLSPDocumentInfoForInvalidDocument() {
		LSPDocumentInfo info = LanguageServiceAccessor.getLSPDocumentInfoFor(new Document(), null);
		assertEquals(null, info);
	}

	@Test
	public void testGetLSPDocumentInfoForInvalidTextEditor() throws CoreException, InvocationTargetException {
		IFile testFile = TestUtils.createFile(project, "not_associated_with_ls.abc", "");
		ITextViewer textViewer = TestUtils.openTextViewer(testFile);
		LSPDocumentInfo info = LanguageServiceAccessor.getLSPDocumentInfoFor(textViewer.getDocument(), capabilities -> Boolean.TRUE);
		assertEquals(null, info);
	}
	
	@Test
	public void testGetLanguageServerInvalidFile() throws Exception {
		IFile testFile = TestUtils.createFile(project, "not_associated_with_ls.abc", "");
		Collection<LanguageServer> servers = LanguageServiceAccessor.getLanguageServers(testFile, capabilites -> Boolean.TRUE);
		assertTrue(servers.isEmpty());
	}

	@Test
	public void testLSAsExtension() throws Exception {
		IFile testFile = TestUtils.createFile(project, "shouldUseExtension.lspt", "");
		LanguageServer info = LanguageServiceAccessor.getLanguageServers(testFile, capabilites -> Boolean.TRUE).iterator().next();
		assertNotNull(info);
	}

	@Test
	public void testLSAsRunConfiguration() throws Exception {
		IFile testFile = TestUtils.createFile(project, "shouldUseRunConfiguration.lspt2", "");
		LanguageServer info = LanguageServiceAccessor.getLanguageServers(testFile, capabilites -> Boolean.TRUE).iterator().next();
		assertNotNull(info);
	}
	
}
