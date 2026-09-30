package com.droiddeck.launcher.gpu;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.droiddeck.launcher.core.FileUtils;
import com.droiddeck.launcher.core.Hashes;
import com.droiddeck.launcher.core.DeviceSupport;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imported Linux Vulkan ICDs for the glibc runtime.
 *
 * <p>Upstream only accepted {@code libvulkan_freedreno*.so}, which made the driver manager itself
 * Adreno-only. The Mali-first fork accepts any AArch64 glibc Vulkan ICD ({@code libvulkan_*.so})
 * and keeps its original filename so PanVK/Panfrost drivers and their helper shared objects can be
 * imported without pretending to be Freedreno.
 *
 * <p>Layout (under the app's files dir, visible at the same absolute path inside the guest):
 * <pre>
 *   files/linux_vulkan_drivers/&lt;id&gt;/
 *       libvulkan_*.so          selected ICD library (original filename retained)
 *       *.so                    optional helper/dependency libraries from the zip
 *       icd.json                generated manifest with absolute library_path
 *       meta.json               name / version / libc / selected library
 * </pre>
 */
public class LinuxVulkanDriverManager {
    private static final String TAG = "LinuxVulkanDriver";
    public static final String DIR_NAME = "linux_vulkan_drivers";
    /** Old imports used this fixed name; retained for migration/compatibility. */
    public static final String LEGACY_LIB_NAME = "libvulkan_freedreno.so";
    public static final String ICD_NAME = "icd.json";
    public static final String META_NAME = "meta.json";

    private final Context context;
    private final File rootDir;

    public LinuxVulkanDriverManager(Context context) {
        this.context = context;
        this.rootDir = new File(context.getFilesDir(), DIR_NAME);
        if (!rootDir.exists()) rootDir.mkdirs();
    }

    public File getDriverDir(String id) {
        return new File(rootDir, id);
    }

    /** Installed = generated manifest plus the selected library recorded in metadata (or legacy). */
    public boolean isInstalled(String id) {
        if (id == null || id.isEmpty() || id.contains("/") || id.contains("..")) return false;
        File dir = getDriverDir(id);
        String lib = installedLibraryName(id);
        return lib != null && new File(dir, lib).isFile() && new File(dir, ICD_NAME).isFile();
    }

    /** Absolute path of the driver's ICD manifest, or null when the id isn't installed. */
    public String getIcdPath(String id) {
        return isInstalled(id) ? new File(getDriverDir(id), ICD_NAME).getAbsolutePath() : null;
    }

    public List<String> enumerateInstalledDrivers() {
        ArrayList<String> ids = new ArrayList<>();
        File[] dirs = rootDir.listFiles();
        if (dirs == null) return ids;
        for (File d : dirs) if (d.isDirectory() && isInstalled(d.getName())) ids.add(d.getName());
        Collections.sort(ids);
        return ids;
    }

    /**
     * Best installed ICD for the current Mali guest ABI. This is used only when the user leaves
     * the runtime driver on Automatic; an explicit selection always wins. Unknown-backend imports
     * are deliberately ranked below packages that declare and match the real Kbase/DRM contract.
     */
    public String bestCompatibleMaliDriver() {
        if (!DeviceSupport.mali()) return "";
        String best = "";
        long bestScore = Long.MIN_VALUE;
        long bestImportedAt = Long.MIN_VALUE;
        DeviceSupport.GuestGpuBackend guestBackend = DeviceSupport.guestGpuBackend();
        String wantedBackend = backendName(guestBackend);
        if (!("mali-kbase".equals(wantedBackend) || "mali-drm".equals(wantedBackend))) return "";
        MaliKbaseProfiles.Profile deviceProfile = guestBackend == DeviceSupport.GuestGpuBackend.MALI_KBASE
                ? MaliKbaseProfiles.forDevice(MaliKbaseProbe.probe()) : null;
        for (String id : enumerateInstalledDrivers()) {
            if (compatibilityIssue(id) != null) continue;
            JSONObject m = readMeta(id);
            String backend = getGuestBackend(id);
            // Automatic must be conservative: an old/ambiguous package with no backend contract
            // may still be selected manually, but it must never silently become the Mali driver.
            if (!wantedBackend.equals(backend)) continue;
            long score = 2000;
            long importedAt = 0;
            if (m != null) {
                String profile = m.optString("maliProfile", "");
                if (deviceProfile != null && deviceProfile.id.equals(profile)) score += 5000;
                if (MaliKbaseProfiles.RELEASE_SOURCE_LABEL.equals(m.optString("source", ""))) score += 500;
                String q = m.optString("qualification", "").toLowerCase(Locale.US);
                if ("stable".equals(q) || "release".equals(q)) score += 80;
                else if ("rc".equals(q)) score += 60;
                else if ("beta".equals(q)) score += 40;
                else if ("alpha".equals(q)) score += 20;
                importedAt = m.optLong("importedAt", 0L);
            }
            if (score > bestScore
                    || (score == bestScore && importedAt > bestImportedAt)
                    || (score == bestScore && importedAt == bestImportedAt
                        && (best.isEmpty() || id.compareToIgnoreCase(best) < 0))) {
                best = id;
                bestScore = score;
                bestImportedAt = importedAt;
            }
        }
        return best;
    }

    private JSONObject readMeta(String id) {
        try {
            File meta = new File(getDriverDir(id), META_NAME);
            if (!meta.isFile()) return null;
            return new JSONObject(FileUtils.readString(meta));
        } catch (Exception e) {
            return null;
        }
    }

    private String installedLibraryName(String id) {
        JSONObject m = readMeta(id);
        String lib = m != null ? m.optString("libraryName", "") : "";
        if (!lib.isEmpty() && !lib.contains("/") && !lib.contains("..")) return lib;
        File legacy = new File(getDriverDir(id), LEGACY_LIB_NAME);
        return legacy.isFile() ? LEGACY_LIB_NAME : null;
    }

    public String getDriverName(String id) {
        JSONObject m = readMeta(id);
        String name = m != null ? m.optString("name", "") : "";
        return name.isEmpty() ? id : name;
    }

    public String getDriverVersion(String id) {
        JSONObject m = readMeta(id);
        return m != null ? m.optString("driverVersion", "") : "";
    }

    /** The glibc the driver asks for, as its zip recorded it ("" when the zip did not say). */
    public String getMinGlibc(String id) {
        JSONObject m = readMeta(id);
        return m != null ? m.optString("minGlibc", "") : "";
    }

    /** Provenance/profile line for drivers installed from a trusted release source. */
    public String getReleaseSummary(String id) {
        JSONObject m = readMeta(id);
        if (m == null) return "";
        ArrayList<String> bits = new ArrayList<>();
        String source = m.optString("source", "");
        String tag = m.optString("sourceTag", "");
        String qualification = m.optString("qualification", "");
        String profile = m.optString("maliProfile", "");
        if (!source.isEmpty()) bits.add(source + (tag.isEmpty() ? "" : " " + tag));
        if (!profile.isEmpty()) bits.add(profile);
        if (!qualification.isEmpty()) bits.add(qualification);
        return String.join(" · ", bits);
    }

    /**
     * Guest kernel ABI required by the package. Values are canonicalized to adreno-kgsl,
     * mali-drm or mali-kbase. Older stage-2 aliases remain readable.
     */
    public String getGuestBackend(String id) {
        JSONObject m = readMeta(id);
        String declared = normalizeBackend(m != null ? m.optString("guestBackend", "") : "");
        if (!declared.isEmpty()) return declared;
        String lib = installedLibraryName(id);
        return lib == null ? "" : inferBackendFromBinary(new File(getDriverDir(id), lib), lib);
    }

    /** Human-readable Kbase profile carried by a driver package, if any. */
    public String getKbaseProfileSummary(String id) {
        JSONObject m = readMeta(id);
        if (m == null || !"mali-kbase".equals(getGuestBackend(id))) return "";
        ArrayList<String> bits = new ArrayList<>();
        String frontend = firstString(m, "maliKbaseFrontend", "kbaseFrontend").toLowerCase(Locale.US);
        if (!frontend.isEmpty()) bits.add(frontend.toUpperCase(Locale.US));
        int major = firstInt(m, -1, "maliKbaseUapiMajor", "kbaseUapiMajor");
        int exactMinor = firstInt(m, -1, "maliKbaseUapiMinor", "kbaseUapiMinor");
        int minMinor = firstInt(m, exactMinor, "maliKbaseMinUapiMinor", "kbaseMinUapiMinor");
        int maxMinor = firstInt(m, exactMinor, "maliKbaseMaxUapiMinor", "kbaseMaxUapiMinor");
        if (major >= 0) {
            if (minMinor >= 0 && maxMinor >= 0 && minMinor != maxMinor)
                bits.add("UAPI " + major + "." + minMinor + "-" + major + "." + maxMinor);
            else if (minMinor >= 0) bits.add("UAPI " + major + "." + minMinor);
            else bits.add("UAPI " + major + ".x");
        }
        List<Long> ids = productIds(m);
        if (!ids.isEmpty()) {
            ArrayList<String> hex = new ArrayList<>();
            for (long v : ids) hex.add(String.format(Locale.US, "0x%04x", v));
            bits.add("GPU " + String.join(",", hex));
        }
        return String.join(" · ", bits);
    }

    /**
     * Exact reason an installed driver cannot run on this phone, or null when the ABI/profile
     * matches. This is intentionally stricter for mali-kbase: a CSF/JM or UAPI mismatch tends to
     * become a hard ioctl failure or black screen rather than a useful Vulkan-loader error.
     */
    public String compatibilityIssue(String id) {
        if (!isInstalled(id)) return "el controlador no está instalado";
        String required = getGuestBackend(id);
        DeviceSupport.GuestGpuBackend actual = DeviceSupport.guestGpuBackend();
        String actualName = backendName(actual);
        if (!required.isEmpty() && !required.equals(actualName)) {
            return "requiere " + required + "; el dispositivo expone " + actualName;
        }
        if (!"mali-kbase".equals(required)) return null;

        MaliKbaseProbe.Result probe = MaliKbaseProbe.probe();
        if (!probe.getUsable()) return "requiere un Kbase /dev/mali0 utilizable";
        JSONObject m = readMeta(id);
        if (m == null) return null;

        String frontend = firstString(m, "maliKbaseFrontend", "kbaseFrontend").toLowerCase(Locale.US);
        if (!frontend.isEmpty() && !frontend.equals(probe.getFrontendName())) {
            return "requiere Kbase " + frontend.toUpperCase(Locale.US)
                    + "; el dispositivo usa " + probe.getFrontend().name();
        }
        int major = firstInt(m, -1, "maliKbaseUapiMajor", "kbaseUapiMajor");
        if (major >= 0 && major != probe.getUapiMajor()) {
            return "requiere Kbase UAPI " + major + ".x; el dispositivo usa " + probe.getUapi();
        }
        int exactMinor = firstInt(m, -1, "maliKbaseUapiMinor", "kbaseUapiMinor");
        int minMinor = firstInt(m, exactMinor, "maliKbaseMinUapiMinor", "kbaseMinUapiMinor");
        int maxMinor = firstInt(m, exactMinor, "maliKbaseMaxUapiMinor", "kbaseMaxUapiMinor");
        if (minMinor >= 0 && probe.getUapiMinor() < minMinor) {
            return "requiere Kbase UAPI >= " + probe.getUapiMajor() + "." + minMinor
                    + "; el dispositivo usa " + probe.getUapi();
        }
        if (maxMinor >= 0 && probe.getUapiMinor() > maxMinor) {
            return "requiere Kbase UAPI <= " + probe.getUapiMajor() + "." + maxMinor
                    + "; el dispositivo usa " + probe.getUapi();
        }
        List<Long> ids = productIds(m);
        if (!ids.isEmpty()) {
            if (probe.getProductCode() == 0L) return "el controlador es específico de GPU, pero no se pudo leer el Product ID de Kbase";
            if (!ids.contains(probe.getProductCode())) {
                return "requiere GPU " + formatProductIds(ids) + "; el dispositivo usa "
                        + probe.getProductCodeHex() + (probe.getGpuId() != 0L
                        ? " (GPU ID " + probe.getGpuIdHex() + ")" : "");
            }
        }
        return null;
    }

    public boolean isCompatibleWithDevice(String id) {
        return compatibilityIssue(id) == null;
    }

    /** The selected ICD library's original basename. */
    public String getLibraryName(String id) {
        String name = installedLibraryName(id);
        return name == null ? "" : name;
    }

    public void removeDriver(String id) {
        if (id == null || id.isEmpty() || id.contains("/") || id.contains("..")) return;
        Log.d(TAG, "removing imported Linux Vulkan driver " + id);
        FileUtils.delete(getDriverDir(id));
    }

    /**
     * Import a checksum-verified release asset and apply profile metadata that is trusted only for
     * the exact repository/tag pair discovered by the in-app release browser.
     */
    public String installReleaseDriver(Uri zipUri, String displayName, String source, String tag) throws IOException {
        String id = installDriver(zipUri, displayName);
        if (!MaliKbaseProfiles.RELEASE_SOURCE_LABEL.equals(source)) return id;
        try {
            JSONObject trusted = MaliKbaseProfiles.trustedReleaseMetadata(tag);
            if (trusted == null) throw new IllegalArgumentException("El perfil de la versión PanVK-Kbase no está validado por esta compilación de DroidDeck: " + tag);
            String lib = installedLibraryName(id);
            if (lib == null || !(lib.toLowerCase(Locale.US).contains("panfrost") || lib.toLowerCase(Locale.US).contains("panvk"))) {
                throw new IllegalArgumentException("La versión PanVK-Kbase no contenía el ICD PanVK/Panfrost esperado");
            }
            JSONObject meta = readMeta(id);
            if (meta == null) throw new IOException("desaparecieron los metadatos del controlador instalado");
            mergeTrustedMetadata(meta, trusted);
            if (!FileUtils.writeString(new File(getDriverDir(id), META_NAME), meta.toString(2)))
                throw new IOException("no se pudieron guardar los metadatos confiables de la versión");
            return id;
        } catch (IllegalArgumentException | IOException e) {
            removeDriver(id);
            throw e;
        } catch (Exception e) {
            removeDriver(id);
            throw new IOException("no se pudo aplicar el perfil confiable de la versión: " + e.getMessage(), e);
        }
    }

    /**
     * Import a zip containing an AArch64 glibc Vulkan ICD. Returns the new driver id.
     *
     * <p>All shared objects are retained because Mesa ICDs can ship helper libraries beside the
     * main driver. The selected ICD must be named {@code libvulkan_*.so}; if metadata names a
     * libraryName that candidate wins, otherwise PanVK/Panfrost/Turnip names are preferred.
     */
    public String installDriver(Uri zipUri, String displayName) throws IOException {
        File tmpDir = new File(rootDir, ".tmp-" + System.currentTimeMillis());
        FileUtils.delete(tmpDir);
        if (!tmpDir.mkdirs()) throw new IOException("no se pudo crear " + tmpDir);
        boolean keep = false;
        try {
            ArrayList<String> candidates = new ArrayList<>();
            JSONObject zipMeta = null;
            try (InputStream is = context.getContentResolver().openInputStream(zipUri);
                 ZipInputStream zis = new ZipInputStream(is)) {
                if (is == null) throw new IOException("no se pudo abrir " + zipUri);
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    // Flatten paths: dependencies still sit beside the ICD, and zip-slip is impossible.
                    String base = new File(entry.getName()).getName();
                    if (base.isEmpty()) continue;
                    if (base.equals(META_NAME)) {
                        try {
                            byte[] data = readAll(zis);
                            Files.write(new File(tmpDir, META_NAME + ".source").toPath(), data);
                            zipMeta = new JSONObject(new String(data, StandardCharsets.UTF_8));
                        } catch (Exception e) {
                            Log.w(TAG, "meta.json unreadable, ignoring: " + e.getMessage());
                        }
                    } else if (base.endsWith(".so") || base.contains(".so.")) {
                        Files.copy(zis, new File(tmpDir, base).toPath(), StandardCopyOption.REPLACE_EXISTING);
                        if (isIcdLibraryName(base)) candidates.add(base);
                    }
                    // Any zip-provided ICD json is intentionally ignored; an absolute manifest is
                    // generated below so cwd and the rootfs's Vulkan loader do not affect resolution.
                }
            }

            verifyPackagedSharedObjects(tmpDir);
            verifyPayloadDigests(tmpDir, zipMeta);

            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("Este ZIP no contiene un ICD Vulkan de Linux (libvulkan_*.so). "
                        + "El runtime necesita un controlador AArch64 glibc como PanVK/Panfrost o Turnip.");
            }

            String declared = zipMeta != null ? zipMeta.optString("libraryName", "") : "";
            String soName = candidates.contains(declared) ? declared : chooseCandidate(candidates);
            File so = new File(tmpDir, soName);
            if (!isAarch64Elf(so)) {
                throw new IllegalArgumentException(soName + " no es una biblioteca compartida ELF AArch64 de 64 bits.");
            }
            // This distinguishes a Linux/glibc ICD from an Android/bionic driver without needing
            // a full ELF dynamic-section parser.
            if (!containsAscii(so, "libc.so.6")) {
                throw new IllegalArgumentException(soName + " no es un controlador glibc; parece enlazar la libc de Android. "
                        + "El runtime Linux no puede cargar un HAL Vulkan de Android ni un controlador AdrenoTools.");
            }

            String kind = zipMeta != null ? zipMeta.optString("kind", "") : "";
            if (!kind.isEmpty() && !"linux-vulkan-icd".equals(kind)) {
                Log.w(TAG, "meta.json says kind=" + kind + ", importing anyway (binary is AArch64 glibc)");
            }

            String name = zipMeta != null ? zipMeta.optString("name", "") : "";
            String version = zipMeta != null ? zipMeta.optString("driverVersion", "") : "";
            String minGlibc = zipMeta != null ? zipMeta.optString("minGlibc", "") : "";
            String gpuFamily = zipMeta != null ? zipMeta.optString("gpuFamily", "") : "";
            String guestBackend = normalizeBackend(zipMeta != null ? zipMeta.optString("guestBackend", "") : "");
            if (guestBackend.isEmpty()) guestBackend = inferBackendFromBinary(so, soName);
            if (name.isEmpty()) {
                name = displayName != null ? displayName : soName;
                if (name.toLowerCase(Locale.US).endsWith(".zip")) name = name.substring(0, name.length() - 4);
            }
            String id = uniqueId(sanitizeId(name));
            File dir = getDriverDir(id);

            JSONObject icd = new JSONObject();
            icd.put("file_format_version", "1.0.0");
            JSONObject icdBody = new JSONObject();
            icdBody.put("library_path", new File(dir, soName).getAbsolutePath());
            icdBody.put("api_version", zipMeta != null ? zipMeta.optString("apiVersion", "1.1.274") : "1.1.274");
            icd.put("ICD", icdBody);
            if (!FileUtils.writeString(new File(tmpDir, ICD_NAME), icd.toString(2))) {
                throw new IOException("no se pudo guardar icd.json");
            }

            JSONObject meta = new JSONObject();
            meta.put("schemaVersion", 4);
            meta.put("kind", "linux-vulkan-icd");
            meta.put("name", name);
            meta.put("driverVersion", version);
            meta.put("libc", "glibc");
            meta.put("minGlibc", minGlibc);
            meta.put("libraryName", soName);
            meta.put("sourceLibraryName", soName);
            if (!gpuFamily.isEmpty()) meta.put("gpuFamily", gpuFamily);
            if (!guestBackend.isEmpty()) meta.put("guestBackend", guestBackend);
            copyKbaseMetadata(zipMeta, meta, guestBackend);
            copyKnownMetadata(zipMeta, meta);
            meta.put("importedAt", System.currentTimeMillis());
            if (!FileUtils.writeString(new File(tmpDir, META_NAME), meta.toString(2))) {
                throw new IOException("no se pudo guardar meta.json");
            }
            // Source metadata was only useful while choosing/importing; do not expose a confusing
            // second meta file to users or future code.
            FileUtils.delete(new File(tmpDir, META_NAME + ".source"));

            if (!tmpDir.renameTo(dir)) throw new IOException("no se pudo mover a " + dir);
            keep = true;
            Log.i(TAG, "imported Linux Vulkan driver " + id + " (" + soName
                    + ", minGlibc=" + minGlibc + ", gpuFamily=" + gpuFamily
                    + ", guestBackend=" + guestBackend + ") -> " + dir);
            return id;
        } catch (org.json.JSONException e) {
            throw new IOException("falló la escritura del manifiesto: " + e.getMessage());
        } finally {
            if (!keep) FileUtils.delete(tmpDir);
        }
    }

    private static String backendName(DeviceSupport.GuestGpuBackend backend) {
        switch (backend) {
            case ADRENO_KGSL: return "adreno-kgsl";
            case MALI_DRM: return "mali-drm";
            case MALI_KBASE: return "mali-kbase";
            default: return "none";
        }
    }

    private static String normalizeBackend(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.US).replace('_', '-');
        if (v.equals("panvk") || v.equals("panfrost") || v.equals("mali-panvk")) return "mali-drm";
        if (v.equals("mali-proprietary") || v.equals("mali-bridge") || v.equals("kbase")
                || v.equals("mali-kbase")) return "mali-kbase";
        if (v.equals("turnip") || v.equals("freedreno") || v.equals("kgsl")
                || v.equals("adreno-kgsl")) return "adreno-kgsl";
        return v;
    }

    /** Binary sniffing is only a migration/default aid; explicit package metadata always wins. */
    private static String inferBackendFromBinary(File so, String name) {
        String l = name.toLowerCase(Locale.US);
        if (containsAscii(so, "/dev/mali0") || containsAscii(so, "mali_kbase")
                || containsAscii(so, "KBASE_IOCTL")) return "mali-kbase";
        if (l.contains("freedreno") || l.contains("turnip") || containsAscii(so, "/dev/kgsl-3d0"))
            return "adreno-kgsl";
        if (l.contains("panvk") || l.contains("panfrost") || l.contains("mali")) return "mali-drm";
        return "";
    }

    private static String firstString(JSONObject m, String... keys) {
        if (m == null) return "";
        for (String key : keys) {
            Object o = m.opt(key);
            if (o != null && o != JSONObject.NULL) {
                String v = String.valueOf(o).trim();
                if (!v.isEmpty()) return v;
            }
        }
        return "";
    }

    private static int firstInt(JSONObject m, int fallback, String... keys) {
        String s = firstString(m, keys);
        if (s.isEmpty()) return fallback;
        try { return Integer.decode(s); } catch (NumberFormatException ignored) { return fallback; }
    }

    private static Long parseLongFlexible(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof Number) return ((Number)value).longValue();
        String s = String.valueOf(value).trim().toLowerCase(Locale.US);
        try {
            if (s.startsWith("0x")) return Long.parseUnsignedLong(s.substring(2), 16);
            return Long.parseLong(s);
        } catch (NumberFormatException ignored) { return null; }
    }

    private static List<Long> productIds(JSONObject m) {
        ArrayList<Long> out = new ArrayList<>();
        if (m == null) return out;
        Object many = m.opt("maliProductIds");
        if (many == null || many == JSONObject.NULL) many = m.opt("productIds");
        if (many instanceof JSONArray) {
            JSONArray a = (JSONArray)many;
            for (int i = 0; i < a.length(); i++) {
                Long v = parseLongFlexible(a.opt(i));
                if (v != null) {
                    long canonical = MaliKbaseProbe.canonicalProductId(v);
                    if (canonical != 0L && !out.contains(canonical)) out.add(canonical);
                }
            }
        } else {
            Long v = parseLongFlexible(many);
            if (v != null) {
                long canonical = MaliKbaseProbe.canonicalProductId(v);
                if (canonical != 0L) out.add(canonical);
            }
        }
        if (out.isEmpty()) {
            Long one = parseLongFlexible(m.opt("maliProductId"));
            if (one == null) one = parseLongFlexible(m.opt("productId"));
            if (one != null) {
                long canonical = MaliKbaseProbe.canonicalProductId(one);
                if (canonical != 0L) out.add(canonical);
            }
        }
        return out;
    }

    private static String formatProductIds(List<Long> ids) {
        ArrayList<String> out = new ArrayList<>();
        for (long id : ids) out.add(String.format(Locale.US, "0x%04x", id));
        return String.join(",", out);
    }

    /** Preserve only the compatibility contract we understand; arbitrary source metadata is ignored. */
    private static void copyKbaseMetadata(JSONObject source, JSONObject dest, String canonicalBackend) throws org.json.JSONException {
        if (source == null || !"mali-kbase".equals(canonicalBackend)) return;
        String frontend = firstString(source, "maliKbaseFrontend", "kbaseFrontend").toLowerCase(Locale.US);
        if (frontend.equals("csf") || frontend.equals("jm")) dest.put("maliKbaseFrontend", frontend);
        int major = firstInt(source, -1, "maliKbaseUapiMajor", "kbaseUapiMajor");
        int exact = firstInt(source, -1, "maliKbaseUapiMinor", "kbaseUapiMinor");
        int min = firstInt(source, exact, "maliKbaseMinUapiMinor", "kbaseMinUapiMinor");
        int max = firstInt(source, exact, "maliKbaseMaxUapiMinor", "kbaseMaxUapiMinor");
        if (major >= 0) dest.put("maliKbaseUapiMajor", major);
        if (min >= 0) dest.put("maliKbaseMinUapiMinor", min);
        if (max >= 0) dest.put("maliKbaseMaxUapiMinor", max);
        List<Long> products = productIds(source);
        if (!products.isEmpty()) {
            JSONArray a = new JSONArray();
            for (long product : products) a.put(String.format(Locale.US, "0x%04x", product));
            dest.put("maliProductIds", a);
        }
    }

    private static void copyKnownMetadata(JSONObject source, JSONObject dest) throws org.json.JSONException {
        if (source == null) return;
        for (String key : new String[]{"abi", "source", "sourceRepo", "sourceTag", "qualification",
                "testedDevice", "maliProfile", "maliPanArch"}) {
            Object value = source.opt(key);
            if (value != null && value != JSONObject.NULL && !String.valueOf(value).trim().isEmpty()) dest.put(key, value);
        }
    }

    private static void mergeTrustedMetadata(JSONObject dest, JSONObject trusted) throws org.json.JSONException {
        java.util.Iterator<String> keys = trusted.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            dest.put(key, trusted.get(key));
        }
    }

    /** All packaged ELF shared objects must target AArch64; mixed-ABI helper payloads fail early. */
    private static void verifyPackagedSharedObjects(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            if (!(n.endsWith(".so") || n.contains(".so."))) continue;
            if (!isAarch64Elf(f))
                throw new IllegalArgumentException(n + " no es una biblioteca compartida ELF AArch64 de 64 bits.");
        }
    }

    /** Optional schema-4 payload hashes catch damaged/repacked manual imports before installation. */
    private static void verifyPayloadDigests(File dir, JSONObject source) throws IOException {
        if (source == null) return;
        JSONObject hashes = source.optJSONObject("filesSha256");
        if (hashes == null) return;
        java.util.Iterator<String> keys = hashes.keys();
        while (keys.hasNext()) {
            String name = keys.next();
            if (name.contains("/") || name.contains("..")) throw new IllegalArgumentException("nombre de archivo de hash no válido: " + name);
            String expected = hashes.optString(name, "").toLowerCase(Locale.US);
            if (!expected.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("SHA-256 no válido para " + name);
            File f = new File(dir, name);
            if (!f.isFile()) throw new IllegalArgumentException("el hash del paquete referencia un archivo que falta: " + name);
            String actual = Hashes.sha256(f);
            if (!expected.equals(actual)) throw new IllegalArgumentException("el SHA-256 no coincide para " + name);
        }
    }

    private static boolean isIcdLibraryName(String base) {
        String l = base.toLowerCase(Locale.US);
        return l.startsWith("libvulkan_") && l.endsWith(".so")
                && !l.contains("wrapper") && !l.contains("wsi");
    }

    private static String chooseCandidate(List<String> candidates) {
        ArrayList<String> sorted = new ArrayList<>(candidates);
        Collections.sort(sorted, (a, b) -> {
            int rankA = candidateRank(a);
            int rankB = candidateRank(b);
            if (rankA != rankB) return Integer.compare(rankA, rankB);
            return a.compareToIgnoreCase(b);
        });
        return sorted.get(0);
    }

    private static int candidateRank(String name) {
        String l = name.toLowerCase(Locale.US);
        if (l.contains("panvk")) return 0;
        if (l.contains("panfrost")) return 1;
        if (l.contains("mali")) return 2;
        if (l.contains("freedreno") || l.contains("turnip")) return 3;
        if (l.contains("virtio") || l.contains("venus")) return 4;
        if (l.contains("lvp") || l.contains("swrast")) return 9;
        return 5;
    }

    private String uniqueId(String base) {
        String id = base;
        int n = 2;
        while (getDriverDir(id).exists()) id = base + "-" + (n++);
        return id;
    }

    static String sanitizeId(String name) {
        String s = name.trim().replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("^[._]+", "");
        if (s.length() > 64) s = s.substring(0, 64);
        return s.isEmpty() ? "linux-driver" : s;
    }

    /** ELF magic, EI_CLASS = 64-bit, EI_DATA = little-endian, e_machine = 0xB7 (AArch64). */
    static boolean isAarch64Elf(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] h = new byte[20];
            int n = 0;
            while (n < h.length) {
                int r = in.read(h, n, h.length - n);
                if (r < 0) break;
                n += r;
            }
            if (n < 20) return false;
            if (h[0] != 0x7f || h[1] != 'E' || h[2] != 'L' || h[3] != 'F') return false;
            if (h[4] != 2 || h[5] != 1) return false;
            int machine = (h[18] & 0xff) | ((h[19] & 0xff) << 8);
            return machine == 0xB7;
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
        return bos.toByteArray();
    }

    static boolean containsAscii(File f, String needle) {
        byte[] nb = needle.getBytes(StandardCharsets.US_ASCII);
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1 << 16];
            int carry = 0;
            int r;
            while ((r = in.read(buf, carry, buf.length - carry)) > 0) {
                int len = carry + r;
                for (int i = 0; i + nb.length <= len; i++) {
                    int j = 0;
                    while (j < nb.length && buf[i + j] == nb[j]) j++;
                    if (j == nb.length) return true;
                }
                carry = Math.min(nb.length - 1, len);
                System.arraycopy(buf, len - carry, buf, 0, carry);
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }
}
