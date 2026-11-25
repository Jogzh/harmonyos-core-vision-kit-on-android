package dev.corevision.android;

import android.content.Context;
import android.content.res.AssetManager;
import android.system.ErrnoException;
import android.system.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.corevision.android.CoreVisionException.Code.RUNTIME_INCOMPATIBLE;
import static dev.corevision.android.CoreVisionException.Code.RUNTIME_MISSING;

final class RuntimePack {
    private static final String ASSETS = "corevision/";
    private static final String MANIFEST = ASSETS + "runtime.sha256";
    private static final String PROFILE = ASSETS + "profile.sha256";
    private static final String OBJECT_PROFILE = ASSETS + "profile-object.sha256";
    private static final String FACE_PROFILE = ASSETS + "profile-face.sha256";
    private static final String SKELETON_PROFILE = ASSETS + "profile-skeleton.sha256";
    private static final String COMPARISON_PROFILE = ASSETS + "profile-face-comparison.sha256";
    private static final String SR_PROFILE = ASSETS + "profile-super-resolution.sha256";
    private static final String OCR_PROFILE = ASSETS + "profile-text-recognition.sha256";
    private static final String SEARCH_PROFILE = ASSETS + "profile-image-search.sha256";
    private static final Pattern LINE = Pattern.compile(
            "([0-9a-f]{64})  (lib/lib[A-Za-z0-9_.+-]+\\.so|models/[A-Za-z0-9_.-]+\\.(?:ms|bin|json))");
    private static final Set<String> REQUIRED_MAPPINGS = new HashSet<>(Arrays.asList("libcorevision_musl.so",
            "libcobackbone.so", "libdlliteclient.so", "libdlliteclient_mindspore_c.so", "libmindspore_lite_ndk.so",
            "libc++_shared.so"));

    final File nativeDir;
    final File modelDir;
    private final Map<String, String> hashes;

    private RuntimePack(File nativeDir, File modelDir, Map<String, String> hashes) {
        this.nativeDir = nativeDir;
        this.modelDir = modelDir;
        this.hashes = hashes;
    }

    static RuntimePack verify(Context context, int operation) throws CoreVisionException {
        AssetManager assets = context.getAssets();
        try {
            byte[] manifest;
            try (InputStream input = assets.open(MANIFEST)) {
                manifest = readManifestBytes(input);
            } catch (FileNotFoundException missing) {
                throw new CoreVisionException(RUNTIME_MISSING, "Host APK is missing the Core Vision runtime bundle");
            }
            Map<String, String> hashes = readManifest(new String(manifest, StandardCharsets.US_ASCII));
            try (InputStream input = assets.open(PROFILE)) {
                Map<String, String> profile = readManifest(new String(readManifestBytes(input), StandardCharsets.US_ASCII));
                if (!hashes.entrySet().containsAll(profile.entrySet())) {
                    throw new IOException("Runtime does not match the supported ABI profile");
                }
            }
            if (operation == Wire.FACE_EMBEDDING) {
                requireProfile(assets, hashes, COMPARISON_PROFILE, "--face-comparison-model");
            }
            if (operation == Wire.DETECT_SKELETONS) {
                requireProfile(assets, hashes, SKELETON_PROFILE, "--include-skeleton");
            }
            if (operation == Wire.DETECT_FACES || operation == Wire.FACE_EMBEDDING || operation == Wire.DETECT_SKELETONS) {
                requireProfile(assets, hashes, FACE_PROFILE, "--include-face");
            }
            if (operation == Wire.DETECT_OBJECTS || operation == Wire.DETECT_FACES
                    || operation == Wire.FACE_EMBEDDING || operation == Wire.DETECT_SKELETONS) {
                requireProfile(assets, hashes, OBJECT_PROFILE, "--include-objects");
            }
            if (operation == Wire.SUPER_RESOLUTION) {
                requireProfile(assets, hashes, SR_PROFILE, "--super-resolution-models");
            }
            if (operation == Wire.RECOGNIZE_TEXT) {
                requireProfile(assets, hashes, OCR_PROFILE, "--text-recognition-models");
            }
            if (operation == Wire.IMAGE_FEATURES || operation == Wire.TEXT_FEATURES) {
                requireProfile(assets, hashes, SEARCH_PROFILE, "--image-search-service");
            }
            File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
            File modelDir = new File(context.getNoBackupFilesDir(), "corevision-" + hex(digest().digest(manifest)));
            if (!modelDir.isDirectory() && !modelDir.mkdirs()) throw new IOException("Cannot create model directory");
            for (Map.Entry<String, String> entry : hashes.entrySet()) {
                String relative = entry.getKey();
                String basename = relative.substring(relative.indexOf('/') + 1);
                if (relative.startsWith("lib/")) {
                    File installed = new File(nativeDir, basename);
                    if (!installed.isFile()) {
                        throw new IOException("Missing installed native file " + basename + ". Enable legacy JNI packaging");
                    }
                    if (!entry.getValue().equals(hash(installed))) {
                        throw new IOException("Installed native SHA-256 mismatch for " + basename);
                    }
                } else {
                    installModel(assets, relative, new File(modelDir, basename), entry.getValue());
                }
            }
            return new RuntimePack(nativeDir, modelDir, hashes);
        } catch (IOException | ErrnoException error) {
            throw new CoreVisionException(RUNTIME_INCOMPATIBLE, error.getMessage(), error);
        }
    }

    private static void requireProfile(AssetManager assets, Map<String, String> hashes, String asset, String option)
            throws IOException, CoreVisionException {
        try (InputStream input = assets.open(asset)) {
            Map<String, String> profile = readManifest(new String(readManifestBytes(input), StandardCharsets.US_ASCII));
            if (!hashes.keySet().containsAll(profile.keySet())) {
                throw new CoreVisionException(RUNTIME_MISSING, "Prepare the runtime with " + option);
            }
            if (!hashes.entrySet().containsAll(profile.entrySet())) {
                throw new IOException("Runtime does not match " + asset);
            }
        }
    }

    String checkedMetadata(String metadata, boolean audit) throws CoreVisionException {
        StringBuilder report = new StringBuilder();
        Set<String> seen = new HashSet<>();
        for (String line : metadata.split("\n")) {
            if (!line.startsWith("loaded=")) {
                report.append(line).append('\n');
                continue;
            }
            String name = line.substring("loaded=".length());
            String path = "lib/" + name;
            if (hashes.containsKey(path)) {
                if (seen.add(name) && audit) report.append("sha256=").append(hashes.get(path)).append("  ").append(path).append('\n');
            } else if (!name.equals("libcorevision_worker.so") && !name.equals("linux-vdso.so.1")) {
                throw new CoreVisionException(RUNTIME_INCOMPATIBLE, "Unverified native mapping " + name);
            }
        }
        if (!seen.containsAll(REQUIRED_MAPPINGS)) {
            throw new CoreVisionException(RUNTIME_INCOMPATIBLE, "Worker did not report all required CPU runtime mappings");
        }
        if (audit) {
            hashes.forEach((path, value) -> {
                if (path.startsWith("models/")) report.append("sha256=").append(value).append("  ").append(path).append('\n');
            });
        }
        return report.toString();
    }

    private static Map<String, String> readManifest(String manifest) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (BufferedReader lines = new BufferedReader(new StringReader(manifest))) {
            String line;
            while ((line = lines.readLine()) != null) {
                Matcher matcher = LINE.matcher(line);
                if (!matcher.matches() || entries.putIfAbsent(matcher.group(2), matcher.group(1)) != null) {
                    throw new IOException("Malformed runtime SHA-256 manifest");
                }
            }
        }
        return entries;
    }

    private static byte[] readManifestBytes(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > 65_536) throw new IOException("Runtime manifest is too large");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void installModel(AssetManager assets, String relative, File destination, String expected)
            throws IOException, ErrnoException {
        if (destination.isFile() && expected.equals(hash(destination))) return;
        File temp = File.createTempFile("model-", ".tmp", destination.getParentFile());
        try {
            MessageDigest digest = digest();
            try (InputStream input = assets.open(ASSETS + relative); FileOutputStream output = new FileOutputStream(temp)) {
                byte[] buffer = new byte[Wire.BUFFER_SIZE];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
            }
            if (!expected.equals(hex(digest.digest()))) throw new IOException("Model SHA-256 mismatch for " + relative);
            Os.rename(temp.getAbsolutePath(), destination.getAbsolutePath());
        } finally {
            temp.delete();
        }
    }

    private static String hash(File file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[Wire.BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value & 255) >>> 4, 16)).append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }
}
