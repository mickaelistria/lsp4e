/*******************************************************************************
 * Copyright (c) 2016-2017 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *  Mickael Istria (Red Hat Inc.) - initial implementation
 *******************************************************************************/
package org.eclipse.lsp4e.operations.hover;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.internal.text.html.BrowserInformationControl;
import org.eclipse.jface.resource.ColorRegistry;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.text.AbstractReusableInformationControlCreator;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DefaultInformationControl;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IInformationControl;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextHover;
import org.eclipse.jface.text.ITextHoverExtension;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Region;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.LanguageServerPlugin;
import org.eclipse.lsp4e.LanguageServiceAccessor;
import org.eclipse.lsp4e.LanguageServiceAccessor.LSPDocumentInfo;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkedString;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.mylyn.wikitext.markdown.MarkdownLanguage;
import org.eclipse.mylyn.wikitext.parser.MarkupParser;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.editors.text.EditorsUI;

public class LSBasedHover implements ITextHover, ITextHoverExtension {

	private static final MarkupParser MARKDOWN_PARSER = new MarkupParser(new MarkdownLanguage());

	private static class FocusableBrowserInformationControl extends BrowserInformationControl {

		public FocusableBrowserInformationControl(Shell parent) {
			super(parent, JFaceResources.DEFAULT_FONT, EditorsUI.getTooltipAffordanceString());
		}

		@Override
		public IInformationControlCreator getInformationPresenterControlCreator() {
			return new IInformationControlCreator() {
				@Override
				public IInformationControl createInformationControl(Shell parent) {
					BrowserInformationControl res = new BrowserInformationControl(parent, JFaceResources.DEFAULT_FONT, true);
					return res;
				}
			};
		}
	}

	private List<CompletableFuture<Hover>> hoverRequests;
	private List<Hover> hoverResults;
	private IRegion lastRegion;
	private ITextViewer textViewer;

	public LSBasedHover() {
	}

	@Override
	public String getHoverInfo(ITextViewer textViewer, IRegion hoverRegion) {
		if (textViewer == null || hoverRegion == null) {
			return null;
		}
		if (!(hoverRegion.equals(this.lastRegion) && textViewer.equals(this.textViewer) && this.hoverRequests != null)) {
			initiateHoverRequest(textViewer, hoverRegion.getOffset());
		}
		try {
			CompletableFuture.allOf(this.hoverRequests.toArray(new CompletableFuture[this.hoverRequests.size()])).get(500, TimeUnit.MILLISECONDS);
		} catch (InterruptedException | ExecutionException | TimeoutException e) {
			LanguageServerPlugin.logError(e);
		}
		if (this.hoverResults == null || this.hoverResults.isEmpty()) {
			return null;
		}
		String result = hoverResults.stream()
				.filter(Objects::nonNull)
				.map(LSBasedHover::getHoverString)
				.filter(Objects::nonNull)
				.collect(Collectors.joining("\n\n")); //$NON-NLS-1$
		if (result == null || result.isEmpty()) {
			return null;
		}
		result = MARKDOWN_PARSER.parseToHtml(result);
		// put CSS styling to match Eclipse style
		ColorRegistry colorRegistry =  JFaceResources.getColorRegistry();
		Color foreground= colorRegistry.get("org.eclipse.ui.workbench.HOVER_FOREGROUND"); //$NON-NLS-1$
		Color background= colorRegistry.get("org.eclipse.ui.workbench.HOVER_BACKGROUND"); //$NON-NLS-1$
		String style = "<style TYPE='text/css'>html { " + //$NON-NLS-1$
				"font-family: " + JFaceResources.getDefaultFontDescriptor().getFontData()[0].getName() + "; " + //$NON-NLS-1$ //$NON-NLS-2$
				"font-size: " + Integer.toString(JFaceResources.getDefaultFontDescriptor().getFontData()[0].getHeight()) + "pt; " + //$NON-NLS-1$ //$NON-NLS-2$
				(background != null ? "background-color: " + toHTMLrgb(background.getRGB()) + "; " : "") + //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				(foreground != null ? "color: " + toHTMLrgb(foreground.getRGB()) + "; " : "") + //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				" }</style>"; //$NON-NLS-1$

		int headIndex = result.indexOf("<head>"); //$NON-NLS-1$
		StringBuilder builder = new StringBuilder(result.length() + style.length());
		builder.append(result.substring(0, headIndex + "<head>".length())); //$NON-NLS-1$
		builder.append(style);
		builder.append(result.substring(headIndex + "<head>".length())); //$NON-NLS-1$
		return builder.toString();
	}

	protected static @Nullable String getHoverString(@NonNull Hover hover) {
		List<Either<String, MarkedString>> contents = hover.getContents();
		if (contents == null || contents.isEmpty()) {
			return null;
		}
		return contents.stream().map(content -> {
			if (content.isLeft()) {
				return content.getLeft();
			} else if (content.isRight()) {
				MarkedString markedString = content.getRight();
				// TODO this won't work fully until markup parser will support syntax highlighting but will help display
				// strings with language tags, e.g. without it things after <?php tag aren't displayed
				if (markedString.getLanguage() != null && !markedString.getLanguage().isEmpty()) {
					return String.format("```%s\n%s\n```", markedString.getLanguage(), markedString.getValue()); //$NON-NLS-1$
				} else {
					return markedString.getValue();
				}
			} else {
				return ""; //$NON-NLS-1$
			}
		}).filter(((Predicate<String>)String::isEmpty).negate()).collect(Collectors.joining("\n\n")); //$NON-NLS-1$)
	}

	private static @NonNull String toHTMLrgb(RGB rgb) {
		StringBuilder builder = new StringBuilder(7);
		builder.append('#');
		appendAsHexString(builder, rgb.red);
		appendAsHexString(builder, rgb.green);
		appendAsHexString(builder, rgb.blue);
		return builder.toString();
	}

	private static void appendAsHexString(StringBuilder buffer, int intValue) {
		String hexValue= Integer.toHexString(intValue);
		if (hexValue.length() == 1) {
			buffer.append('0');
		}
		buffer.append(hexValue);
	}

	@Override
	public IRegion getHoverRegion(ITextViewer textViewer, int offset) {
		if (textViewer == null) {
			return null;
		}
		IRegion res = null;
		initiateHoverRequest(textViewer, offset);
		try {
			CompletableFuture.allOf(this.hoverRequests.toArray(new CompletableFuture[this.hoverRequests.size()])).get(800, TimeUnit.MILLISECONDS);
		} catch (InterruptedException | ExecutionException | TimeoutException e1) {
			LanguageServerPlugin.logError(e1);
		}
		final IDocument document = textViewer.getDocument();
		if (!this.hoverResults.isEmpty()) {
			res = new Region(0, document.getLength());
			for (Hover hover : this.hoverResults) {
				int rangeOffset = offset;
				int rangeLength = 0;
				if (hover != null && hover.getRange() != null) {
					try {
						Range range = hover.getRange();
						rangeOffset = LSPEclipseUtils.toOffset(range.getStart(), document);
						rangeLength = LSPEclipseUtils.toOffset(range.getEnd(), document) - rangeOffset;
					} catch (BadLocationException e) {
						LanguageServerPlugin.logError(e);
						res = new Region(offset, 1);
					}
				}
				res = new Region(
						Math.max(res.getOffset(), rangeOffset),
						Math.min(res.getLength(), rangeLength));
			}
		} else {
			res = new Region(offset, 1);
		}

		this.lastRegion = res;
		this.textViewer = textViewer;
		return res;
	}

	private void initiateHoverRequest(@NonNull ITextViewer viewer, int offset) {
		this.textViewer = viewer;
		List<@NonNull LSPDocumentInfo> docInfos = LanguageServiceAccessor.getLSPDocumentInfosFor(viewer.getDocument(), (capabilities) -> Boolean.TRUE.equals(capabilities.getHoverProvider()));
		// use intermediary variables to make the lists specific to the request
		// if we directly add/remove from members, we may have thread related issues such as some
		// results from a previous request leaking in the new hover.
		final List<CompletableFuture<Hover>> requests = new ArrayList<>(docInfos.size());
		final List<Hover> result = Collections.synchronizedList(new ArrayList<>(docInfos.size()));
		for (@NonNull final LSPDocumentInfo info : docInfos) {
			try {
				CompletableFuture<Hover> hover = info.getLanguageClient().getTextDocumentService().hover(LSPEclipseUtils.toTextDocumentPosistionParams(info.getFileUri(), offset, info.getDocument()));
				hover.thenAccept(result::add);
				requests.add(hover);
			} catch (BadLocationException e) {
				LanguageServerPlugin.logError(e);
			}
		}
		this.hoverRequests = requests;
		this.hoverResults = result;
	}

	@Override
	public IInformationControlCreator getHoverControlCreator() {
		return new AbstractReusableInformationControlCreator() {
			@Override
			protected IInformationControl doCreateInformationControl(Shell parent) {
				if (BrowserInformationControl.isAvailable(parent)) {
					return new FocusableBrowserInformationControl(parent);
				} else {
					return new DefaultInformationControl(parent);
				}
			}
		};
	}
}
