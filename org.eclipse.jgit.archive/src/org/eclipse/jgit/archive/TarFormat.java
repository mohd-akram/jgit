/*
 * Copyright (C) 2012 Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.archive;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.OutputStream;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.commons.compress.archivers.ArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.archivers.tar.TarUtils;
import org.apache.commons.compress.archivers.zip.ZipEncoding;
import org.eclipse.jgit.api.ArchiveCommand;
import org.eclipse.jgit.archive.internal.ArchiveText;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.revwalk.RevCommit;


/**
 * Unix TAR format (ustar + some PAX extensions).
 */
public final class TarFormat extends BaseFormat implements
		ArchiveCommand.Format<ArchiveOutputStream> {
	private static final List<String> SUFFIXES = Collections
			.unmodifiableList(Arrays.asList(".tar")); //$NON-NLS-1$

	private class TarArchiveEntry extends org.apache.commons.compress.archivers.tar.TarArchiveEntry {
		TarArchiveEntry(final String name, final byte linkFlag) {
			super(name, linkFlag);
			setNames("root", "root");
		}
		TarArchiveEntry(final String name) {
			super(name);
			setNames("root", "root");
		}
		public void writeEntryHeader(final byte[] outbuf, final ZipEncoding encoding,
			final boolean starMode) throws IOException {
			// Terminate numeric fields with NUL instead of Space to match git
			super.writeEntryHeader(outbuf, encoding, starMode);
			int offset = NAMELEN + MODELEN - 1;
			outbuf[offset] = 0;
			offset += UIDLEN;
			outbuf[offset] = 0;
			offset += GIDLEN;
			outbuf[offset] = 0;
			offset += SIZELEN;
			outbuf[offset] = 0;
			offset += MODTIMELEN;
			outbuf[offset] = 0;
			final int csOffset = ++offset;
			for (int c = 0; c < CHKSUMLEN; ++c) {
				outbuf[offset++] = (byte) ' ';
			}
			offset += NAMELEN + MAGICLEN + VERSIONLEN +
				UNAMELEN + GNAMELEN + DEVLEN;
			outbuf[offset] = 0;
			offset += DEVLEN;
			outbuf[offset] = 0;
			final long chk = TarUtils.computeCheckSum(outbuf);
			TarUtils.formatUnsignedOctalString(chk, outbuf, csOffset, CHKSUMLEN - 1);
			outbuf[csOffset + CHKSUMLEN - 1] = 0;
		}
	}

	/** {@inheritDoc} */
	@Override
	public ArchiveOutputStream createArchiveOutputStream(OutputStream s)
			throws IOException {
		return createArchiveOutputStream(s,
				Collections.<String, Object> emptyMap());
	}

	/** {@inheritDoc} */
	@Override
	public ArchiveOutputStream createArchiveOutputStream(OutputStream s,
			Map<String, Object> o) throws IOException {
		TarArchiveOutputStream out = new TarArchiveOutputStream(s,
				10240, UTF_8.name());
		out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
		out.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
		return applyFormatOptions(out, o);
	}

	/** {@inheritDoc} */
	@Override
	public void putEntry(ArchiveOutputStream out,
			ObjectId tree, String path, FileMode mode, ObjectLoader loader)
			throws IOException {
		boolean isCommit = tree instanceof RevCommit;
		long t = isCommit ?
			((RevCommit) tree).getCommitTime() * 1000L :
			System.currentTimeMillis();

		if (out.getBytesWritten() == 0 && isCommit) {
			final TarArchiveEntry entry = new TarArchiveEntry(
				"pax_global_header",
				TarConstants.LF_PAX_GLOBAL_EXTENDED_HEADER
			);
			entry.setModTime(t);
			entry.setMode(0666);
			entry.addPaxHeader("comment", ObjectId.toString(tree));
			out.putArchiveEntry(entry);
		}

		if (mode == FileMode.SYMLINK) {
			final TarArchiveEntry entry = new TarArchiveEntry(
					path, TarConstants.LF_SYMLINK);
			entry.setModTime(t);
			entry.setMode(0777);
			entry.setLinkName(new String(loader.getCachedBytes(100), UTF_8));
			out.putArchiveEntry(entry);
			out.closeArchiveEntry();
			return;
		}

		// TarArchiveEntry detects directories by checking
		// for '/' at the end of the filename.
		if (path.endsWith("/") && mode != FileMode.TREE) //$NON-NLS-1$
			throw new IllegalArgumentException(MessageFormat.format(
					ArchiveText.get().pathDoesNotMatchMode, path, mode));
		if (!path.endsWith("/") && mode == FileMode.TREE) //$NON-NLS-1$
			path = path + "/"; //$NON-NLS-1$

		final TarArchiveEntry entry = new TarArchiveEntry(path);
		entry.setModTime(t);

		if (mode == FileMode.TREE) {
			entry.setMode(0775);
			out.putArchiveEntry(entry);
			out.closeArchiveEntry();
			return;
		}

		if (mode == FileMode.REGULAR_FILE) {
			entry.setMode(0664);
		} else if (mode == FileMode.EXECUTABLE_FILE) {
			entry.setMode(0775);
		} else {
			// Unsupported mode (e.g., GITLINK).
			throw new IllegalArgumentException(MessageFormat.format(
					ArchiveText.get().unsupportedMode, mode));
		}
		entry.setSize(loader.getSize());
		out.putArchiveEntry(entry);
		loader.copyTo(out);
		out.closeArchiveEntry();
	}

	/** {@inheritDoc} */
	@Override
	public Iterable<String> suffixes() {
		return SUFFIXES;
	}

	/** {@inheritDoc} */
	@Override
	public boolean equals(Object other) {
		return (other instanceof TarFormat);
	}

	/** {@inheritDoc} */
	@Override
	public int hashCode() {
		return getClass().hashCode();
	}
}
