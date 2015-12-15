/*******************************************************************************
 * Copyright (c) 2015 Sebastian Stenzel and others.
 * This file is licensed under the terms of the MIT license.
 * See the LICENSE.txt file for more info.
 *
 * Contributors:
 *     Sebastian Stenzel - initial API and implementation
 *******************************************************************************/
package org.cryptomator.crypto.fs;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.apache.commons.codec.binary.Base32;
import org.apache.commons.lang3.StringUtils;
import org.cryptomator.crypto.engine.Cryptor;
import org.cryptomator.filesystem.File;
import org.cryptomator.filesystem.Folder;
import org.cryptomator.filesystem.FolderCreateMode;
import org.cryptomator.filesystem.Node;
import org.cryptomator.filesystem.ReadableFile;
import org.cryptomator.filesystem.WritableFile;

class CryptoFolder extends CryptoNode implements Folder {

	static final String FILE_EXT = ".dir";

	private final AtomicReference<String> directoryId = new AtomicReference<>();

	public CryptoFolder(CryptoFolder parent, String name, Cryptor cryptor) {
		super(parent, name, cryptor);
	}

	@Override
	String encryptedName() {
		return name() + FILE_EXT;
	}

	protected String getDirectoryId() {
		if (directoryId.get() == null) {
			File dirFile = physicalFile();
			if (dirFile.exists()) {
				try (ReadableFile readable = dirFile.openReadable(1, TimeUnit.SECONDS)) {
					final ByteBuffer buf = ByteBuffer.allocate(64);
					readable.read(buf);
					buf.flip();
					byte[] bytes = new byte[buf.remaining()];
					buf.get(bytes);
					directoryId.set(new String(bytes));
				} catch (TimeoutException e) {
					throw new UncheckedIOException(new IOException("Failed to lock directory file in time." + dirFile, e));
				}
			} else {
				directoryId.compareAndSet(null, UUID.randomUUID().toString());
			}
		}
		return directoryId.get();
	}

	File physicalFile() {
		return parent.physicalFolder().file(encryptedName());
	}

	Folder physicalFolder() {
		final String encryptedThenHashedDirId;
		try {
			final byte[] hash = MessageDigest.getInstance("SHA-1").digest(getDirectoryId().getBytes());
			encryptedThenHashedDirId = new Base32().encodeAsString(hash);
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError("SHA-1 exists in every JVM");
		}
		// TODO actual encryption
		return physicalDataRoot().folder(encryptedThenHashedDirId.substring(0, 2)).folder(encryptedThenHashedDirId.substring(2));
	}

	@Override
	public Instant lastModified() {
		return physicalFile().lastModified();
	}

	@Override
	public Stream<? extends Node> children() {
		return Stream.concat(files(), folders());
	}

	@Override
	public Stream<CryptoFile> files() {
		return physicalFolder().files().map(File::name).filter(s -> s.endsWith(CryptoFile.FILE_EXT)).map(this::decryptFileName).map(this::file);
	}

	private String decryptFileName(String encryptedFileName) {
		final String ciphertext = StringUtils.removeEnd(encryptedFileName, CryptoFile.FILE_EXT);
		return cryptor.getFilenameCryptor().decryptFilename(ciphertext);
	}

	@Override
	public CryptoFile file(String name) {
		return new CryptoFile(this, name, cryptor);
	}

	@Override
	public Stream<CryptoFolder> folders() {
		return physicalFolder().files().map(File::name).filter(s -> s.endsWith(CryptoFolder.FILE_EXT)).map(this::decryptFolderName).map(this::folder);
	}

	private String decryptFolderName(String encryptedFolderName) {
		final String ciphertext = StringUtils.removeEnd(encryptedFolderName, CryptoFolder.FILE_EXT);
		return cryptor.getFilenameCryptor().decryptFilename(ciphertext);
	}

	@Override
	public CryptoFolder folder(String name) {
		return new CryptoFolder(this, name, cryptor);
	}

	@Override
	public void create(FolderCreateMode mode) {
		final File dirFile = physicalFile();
		if (dirFile.exists()) {
			return;
		}
		if (!parent.exists() && FolderCreateMode.FAIL_IF_PARENT_IS_MISSING.equals(mode)) {
			throw new UncheckedIOException(new FileNotFoundException(parent.name));
		} else if (!parent.exists() && FolderCreateMode.INCLUDING_PARENTS.equals(mode)) {
			parent.create(mode);
		}
		assert parent.exists();
		final String directoryId = getDirectoryId();
		try (WritableFile writable = dirFile.openWritable(1, TimeUnit.SECONDS)) {
			final ByteBuffer buf = ByteBuffer.wrap(directoryId.getBytes());
			writable.write(buf);
		} catch (TimeoutException e) {
			throw new UncheckedIOException(new IOException("Failed to lock directory file in time." + dirFile, e));
		}
		physicalFolder().create(FolderCreateMode.INCLUDING_PARENTS);
	}

	@Override
	public void copyTo(Folder target) {
		if (this.contains(target)) {
			throw new IllegalArgumentException("Can not copy parent to child directory (src: " + this + ", dst: " + target + ")");
		}

		Folder.super.copyTo(target);
	}

	@Override
	public void moveTo(Folder target) {
		if (target instanceof CryptoFolder) {
			moveToInternal((CryptoFolder) target);
		} else {
			throw new UnsupportedOperationException("Can not move CryptoFolder to conventional folder.");
		}
	}

	private void moveToInternal(CryptoFolder target) {
		if (this.contains(target) || target.contains(this)) {
			throw new IllegalArgumentException("Can not move directories containing one another (src: " + this + ", dst: " + target + ")");
		}

		target.physicalFile().parent().get().create(FolderCreateMode.INCLUDING_PARENTS);
		assert target.physicalFile().parent().get().exists();
		try (WritableFile src = this.physicalFile().openWritable(1, TimeUnit.SECONDS); WritableFile dst = target.physicalFile().openWritable(1, TimeUnit.SECONDS)) {
			src.moveTo(dst);
		} catch (TimeoutException e) {
			throw new UncheckedIOException(new IOException("Failed to lock file for moving (src: " + this + ", dst: " + target + ")", e));
		}
		// directoryId is now used by target, we must no longer use the same id (we'll generate a new one when needed)
		directoryId.set(null);
	}

	private boolean contains(Node node) {
		Optional<? extends Folder> nodeParent = node.parent();
		while (nodeParent.isPresent()) {
			if (this.equals(nodeParent.get())) {
				return true;
			}
			nodeParent = nodeParent.get().parent();
		}
		return false;
	}

	@Override
	public void delete() {
		// TODO Auto-generated method stub

	}

	@Override
	public String toString() {
		return parent.toString() + name + "/";
	}

}
