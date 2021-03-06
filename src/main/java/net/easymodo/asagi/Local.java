package net.easymodo.asagi;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.easymodo.asagi.model.Media;
import net.easymodo.asagi.model.MediaPost;
import net.easymodo.asagi.model.Page;
import net.easymodo.asagi.model.Topic;
import org.apache.http.annotation.ThreadSafe;

import com.google.common.io.ByteStreams;
import com.sun.jna.Native;
import com.sun.jna.Platform;

import net.easymodo.asagi.settings.BoardSettings;
import net.easymodo.asagi.exception.*;
import net.easymodo.asagi.posix.*;

@ThreadSafe
public class Local extends Board {
    private final String path;
    private final boolean useOldDirectoryStructure;
    private final int webGroupId;

    private final static int DIR_THUMB = 1;
    private final static int DIR_MEDIA = 2;
    private final static int FULL_FILE = 1;
    private final static int TEMP_FILE = 2;

    private final static Posix posix;
    private DB db;

    private final static Pattern oldDirectoryMatchingPattern = Pattern.compile("(\\d+?)(\\d{2})\\d{0,3}$");

    static {
        if(Platform.isWindows()) {
          posix = null;
        } else {
          posix = (Posix)Native.loadLibrary("c", Posix.class);
        }
    }

    public Local(String path, BoardSettings info, DB db) {
        this.path = path;
        this.useOldDirectoryStructure = info.getUseOldDirectoryStructure();
        this.db = db;

        // getgrnam is thread-safe on sensible OSes, but it's not thread safe
        // on most ones.
        // Performing the gid lookup in the constructor and calling chmod and
        // chown from the C library (which are reentrant functions) keeps this
        // class thread-safe.
        String webServerGroup = info.getWebserverGroup();
        if(webServerGroup != null && posix != null) {
            Group group = posix.getgrnam(webServerGroup);
            if(group == null)
                webGroupId = 0;
            else
                webGroupId = (int)group.getGid();
        } else {
            webGroupId = 0;
        }
    }

    @Override
    public Page getPage(int pageNum, String lastMod) {
        // Unimplemented
        throw new UnsupportedOperationException();
    }

    @Override
    public Topic getThread(int threadNum, String lastMod) {
        // Unimplemented
        throw new UnsupportedOperationException();
    }

    @Override
    public Page getAllThreads(String lastMod) throws ContentGetException {
        // Unimplemented
        throw new UnsupportedOperationException();
    }

    @Override
    public InputStream getMediaPreview(MediaPost h) {
        // Unimplemented
        throw new UnsupportedOperationException();
    }

    @Override
    public InputStream getMedia(MediaPost h) {
        // Unimplemented
        throw new UnsupportedOperationException();
    }

    public String[] getSubdirs(String filename) {
        String subdir = filename.substring(0, 4);
        String subdir2 = filename.substring(4, 6);

        return new String[] {subdir, subdir2};
    }

    public String[] getSubdirs(MediaPost h) {
        Matcher mat = oldDirectoryMatchingPattern.matcher(Integer.toString(h.getThreadNum()));
        mat.find();

        String subdir = String.format("%04d", Integer.parseInt(mat.group(1)));
        String subdir2 = String.format("%02d", Integer.parseInt(mat.group(2)));

        return new String[] {subdir, subdir2};
    }

    public String getDir(String[] subdirs, int dirType, int fileType) {
        if(fileType == TEMP_FILE) {
            return String.format("%s/tmp", this.path);
        } else if(fileType == FULL_FILE) {
            if(dirType == DIR_THUMB) {
                return String.format("%s/thumb/%s/%s", this.path, subdirs[0], subdirs[1]);
            } else if(dirType == DIR_MEDIA) {
                return String.format("%s/image/%s/%s", this.path, subdirs[0], subdirs[1]);
            } else {
                return null;
            }
        } else {
            return null;
        }
    }

    public String makeDir(String filename, int dirType, int fileType) throws ContentStoreException {
        String[] subdirs = this.getSubdirs(filename);
        return this.makeDir(subdirs, dirType, fileType);
    }

    public String makeDir(MediaPost h, int dirType, int fileType) throws ContentStoreException {
        String[] subdirs = this.getSubdirs(h);
        return this.makeDir(subdirs, dirType, fileType);
    }

    public String makeDir(String[] subdirs, int dirType, int fileType) throws ContentStoreException {
        String dir;
        if(dirType == DIR_THUMB) {
            dir = "thumb";
        } else if(dirType == DIR_MEDIA) {
            dir = "image";
        } else {
            return null;
        }

        String subDir = String.format("%s/%s/%s", this.path, dir, subdirs[0]);
        String subDir2 = String.format("%s/%s/%s/%s", this.path, dir, subdirs[0], subdirs[1]);
        File subDir2File = new File(subDir2);

        String tempDir = String.format("%s/%s", this.path, "tmp");
        File tempDirFile = new File(tempDir);

        synchronized(this) {
            if(!subDir2File.exists())
                if(!subDir2File.mkdirs())
                    throw new ContentStoreException("Could not create dirs at path " + subDir2);

            if(!tempDirFile.exists())
                if(!tempDirFile.mkdirs())
                    throw new ContentStoreException("Could not create temp dir at path " + tempDir);

            if(this.webGroupId != 0) {
                posix.chmod(subDir, 0775);
                posix.chmod(subDir2, 0775);
                posix.chown(subDir, -1, this.webGroupId);
                posix.chown(subDir2, -1, this.webGroupId);

                posix.chmod(tempDir, 0775);
                posix.chown(tempDir, -1, this.webGroupId);
            }
        }

        return this.getDir(subdirs, dirType, fileType);
    }

    public void insert(Topic topic) throws ContentStoreException, DBConnectionException {
        this.db.insert(topic);
    }

    public void markDeleted(int post) throws ContentStoreException {
        try{
            this.db.markDeleted(post);
        } catch(DBConnectionException e) {
            throw new ContentStoreException("Lost connection to database, can't reconnect", e);
        }
    }

    public void insertMediaPreview(MediaPost h, Board source) throws ContentGetException, ContentStoreException {
        this.insertMedia(h, source, true);
    }

    public void insertMedia(MediaPost h, Board source) throws ContentGetException, ContentStoreException {
        this.insertMedia(h, source, false);
    }

    public void insertMedia(MediaPost h, Board source, boolean isPreview) throws ContentGetException, ContentStoreException {
        // Post has no media
        if((isPreview && h.getPreview() == null) || (!isPreview && h.getMedia() == null))
            return;

        Media mediaRow;
        try {
            mediaRow = db.getMedia(h);
        } catch(DBConnectionException e) {
            throw new ContentStoreException("Lost connection to database, can't reconnect", e);
        }

        // Media is banned from archiving
        if(mediaRow.getBanned() == 1) return;

        // Get the proper filename for the file type we're outputting
        String filename;
        if(this.useOldDirectoryStructure)
            filename = isPreview ? h.getPreview() : h.getMedia();
        else
            filename = isPreview ? (h.isOp() ?  mediaRow.getPreviewOp() : mediaRow.getPreviewReply()) :
                mediaRow.getMedia();

        if(filename == null) return;

        // Create the dir structure (if necessary) and return the path to where we're outputting our file
        // Filename is enough for us here, we just need the first part of the string
        String outputDir;
        if(this.useOldDirectoryStructure)
            outputDir = makeDir(h, isPreview ? DIR_THUMB : DIR_MEDIA, FULL_FILE);
        else
            outputDir = makeDir(filename, isPreview ? DIR_THUMB : DIR_MEDIA, FULL_FILE);

        // Construct the path and back down if the file already exists
        File outputFile = new File(outputDir + "/" + filename);
        if(outputFile.exists()) {
            if (!isPreview) outputFile.setLastModified(System.currentTimeMillis());
            return;
        }

        // Open a temp file for writing
        String tempFilePath;
        if(this.useOldDirectoryStructure)
            tempFilePath = makeDir(h, isPreview ? DIR_THUMB : DIR_MEDIA, TEMP_FILE);
        else
            tempFilePath = makeDir(filename, isPreview ? DIR_THUMB : DIR_MEDIA, TEMP_FILE);


        // Throws ContentGetException on failure
        InputStream inStream = isPreview ? source.getMediaPreview(h) : source.getMedia(h);

        OutputStream outFile = null;
        File tempFile = null;
        try {
             tempFile = File.createTempFile(filename + "_", null, new File(tempFilePath + "/"));

            outFile = new BufferedOutputStream(new FileOutputStream(tempFile));

            // Copy the network input stream to our local file
            // In case the connection is cut off or something similar happens, an IOException
            // will be thrown.
            ByteStreams.copy(inStream, outFile);
        } catch(FileNotFoundException e) {
            throw new ContentStoreException("The temp file we just created wasn't there!", e);
        } catch(IOException e) {
            if(tempFile != null && !tempFile.delete())
                System.err.println("Additionally, temporary file " + tempFilePath + "/" + filename + " could not be deleted.");
                e.printStackTrace();
            throw new ContentStoreException("IOException in file download", e);
        } finally {
            try {
                if(outFile != null) outFile.close();
                inStream.close();
            } catch(IOException e) {
                System.err.println("IOException trying to close streams after file download");
                e.printStackTrace();
            }
        }

        // Move the temporary file into place
        if(!tempFile.renameTo(outputFile))
            throw new ContentStoreException("Unable to move temporary file " + tempFilePath + "/" + filename + " into place");

        try {
            if(this.webGroupId != 0) {
                posix.chmod(outputFile.getCanonicalPath(), 0664);
                posix.chown(outputFile.getCanonicalPath(), -1, this.webGroupId);
            }
        } catch(IOException e) {
            throw new ContentStoreException("IOException trying to get filename for output file (nice broken filesystem you have there)", e);
        }
    }
}

