package com.droiddeck.launcher.gpu;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Known mali_kbase profiles and the level at which DroidDeck can safely use them.
 *
 * <p>This is deliberately a hardware-evidence matrix, not a broad "Mali compatible" list.  A
 * profile may be known from public driver work without being downloadable by DroidDeck: the Linux
 * guest needs an AArch64 <b>glibc</b> ICD, while many Android driver projects only publish Bionic
 * packages.  Keeping those states separate prevents a working Android PanVK ZIP from being offered
 * to the glibc guest where it cannot load.</p>
 */
public final class MaliKbaseProfiles {
    public static final String RELEASE_SOURCE_LABEL = "PanVK-Kbase";
    public static final String RELEASE_REPO = "zenithblue-oss/panvk-kbase-android";

    // Pinned experimental glibc build for Valhall v9 / Job Manager. Unlike Winlator/AdrenoTools
    // packages, this asset is an AArch64 GNU/Linux ICD and can be loaded directly by our PRoot
    // runtime. Keep URL + checksum immutable so "Automatic" never turns into a moving target.
    public static final String G57_RELEASE_SOURCE_LABEL = "PanVK-G57-glibc";
    public static final String G57_RELEASE_REPO = "apexspan-svg/mesa-panvk-mali-g57";
    public static final String G57_RELEASE_TAG = "v1.0.0-async";
    public static final String G57_RELEASE_URL =
            "https://github.com/apexspan-svg/mesa-panvk-mali-g57/releases/download/v1.0.0-async/"
                    + "panvk-mali-g57-v1.0.0-glibc-async.zip";
    public static final String G57_RELEASE_SHA256 =
            "32f2241c2eba26b87d8dc7fa6c17fe4a2bffd7de8e60596c6203fc7b1f4d247e";

    public enum Maturity {
        QUALIFIED_GLIBC,
        PUBLIC_EXPERIMENTAL,
        BRINGUP,
        UNKNOWN
    }

    public static final class Profile {
        public final String id;
        public final String displayGpu;
        public final int panArch;
        public final String frontend;
        public final int uapiMajor;
        public final int uapiMinMinor;
        public final int uapiMaxMinor;
        /** Canonical 16-bit product codes, never full revision-bearing GPU IDs. */
        public final long[] productIds;
        public final String testedDevice;
        public final Maturity maturity;
        public final boolean glibcReleaseIntegrated;

        Profile(String id, String displayGpu, int panArch, String frontend,
                int uapiMajor, int uapiMinMinor, int uapiMaxMinor,
                long[] productIds, String testedDevice, Maturity maturity,
                boolean glibcReleaseIntegrated) {
            this.id = id;
            this.displayGpu = displayGpu;
            this.panArch = panArch;
            this.frontend = frontend;
            this.uapiMajor = uapiMajor;
            this.uapiMinMinor = uapiMinMinor;
            this.uapiMaxMinor = uapiMaxMinor;
            this.productIds = productIds;
            this.testedDevice = testedDevice;
            this.maturity = maturity;
            this.glibcReleaseIntegrated = glibcReleaseIntegrated;
        }

        public boolean matches(MaliKbaseProbe.Result probe) {
            if (probe == null || !probe.getUsable()) return false;
            if (!frontend.isEmpty() && !frontend.equalsIgnoreCase(probe.getFrontendName())) return false;
            if (uapiMajor >= 0 && uapiMajor != probe.getUapiMajor()) return false;
            if (uapiMinMinor >= 0 && probe.getUapiMinor() < uapiMinMinor) return false;
            if (uapiMaxMinor >= 0 && probe.getUapiMinor() > uapiMaxMinor) return false;
            long product = probe.getProductCode();
            if (productIds.length == 0) return true;
            for (long id : productIds) if (id == product) return true;
            return false;
        }
    }

    // Public beta.3 reference profile: Poco X6 Pro / Dimensity 8300-Ultra, Mali-G615 MC6.
    // Public logs often spell the complete GPU ID as 0xb8a31030; Kbase's product code is 0xb8a3.
    private static final Profile G615_V11_CSF = new Profile(
            "g615-v11-csf", "Mali-G615", 11, "csf",
            1, 21, 21, new long[]{0xb8a3L},
            "Poco X6 Pro / Dimensity 8300-Ultra",
            Maturity.QUALIFIED_GLIBC, true
    );

    // Public Beta 2 reference hardware reports full GPU ID 0xc8700010 and Kbase/CSF UK/UAPI
    // 1.30.  Windows/DXVK coverage is still experimental, so this profile is recognized for exact
    // matching and manual glibc packaging but is not offered as an integrated download.
    private static final Profile G720_V12_CSF = new Profile(
            "g720-v12-csf", "Mali-G720", 12, "csf",
            1, 30, 30, new long[]{0xc870L},
            "Mali-G720 MC8 / MediaTek MT6899",
            Maturity.PUBLIC_EXPERIMENTAL, false
    );

    // Valhall v9 / JM. Public hardware logs identify Mali-G57 as GPU ID 0x90930010,
    // canonical product 0x9093. The pinned glibc package is explicitly built for PRoot/containers.
    // We intentionally match UAPI major 11 but do not freeze the minor: public G57/JM devices span
    // at least 11.38 and 11.46, and the real Vulkan/Zink/gamescope preflight remains authoritative.
    private static final Profile G57_V9_JM = new Profile(
            "g57-v9-jm", "Mali-G57", 9, "jm",
            11, -1, -1, new long[]{0x9091L, 0x9093L},
            "Mali-G57 JM / Unisoc T820 (0x9091) + public G57 validation (0x9093)",
            Maturity.PUBLIC_EXPERIMENTAL, true
    );

    // Public G52/JM work reports full GPU ID 0x74021000 -> product 0x7402, Kbase UAPI 11.38.
    // WSI and basic DXVK rendering have public evidence, but several advertised Vulkan features
    // remain experimental, so this is recognition/manual packaging only.
    private static final Profile G52_V7_JM = new Profile(
            "g52-v7-jm", "Mali-G52 r1", 7, "jm",
            11, 38, 38, new long[]{0x7402L},
            "Mali-G52 r1 MC2 / MediaTek MT6768",
            Maturity.PUBLIC_EXPERIMENTAL, false
    );

    private static final Profile[] KNOWN = new Profile[]{G615_V11_CSF, G720_V12_CSF, G57_V9_JM, G52_V7_JM};

    private MaliKbaseProfiles() {}

    /** Known hardware profile for the current Kbase probe, even when no guest driver is bundled. */
    public static Profile forDevice(MaliKbaseProbe.Result probe) {
        for (Profile p : KNOWN) if (p.matches(probe)) return p;
        return null;
    }

    /** User-facing readiness line. It never upgrades experimental evidence to compatibility. */
    public static String readiness(MaliKbaseProbe.Result probe) {
        if (probe == null || !probe.getUsable()) return "Kbase no utilizable";
        Profile p = forDevice(probe);
        if (p != null) {
            switch (p.maturity) {
                case QUALIFIED_GLIBC:
                    return p.displayGpu + " reconocida · perfil glibc PanVK-Kbase calificado disponible";
                case PUBLIC_EXPERIMENTAL:
                    return p.glibcReleaseIntegrated
                            ? p.displayGpu + " JM reconocida · PanVK glibc experimental disponible; se validará antes de abrir Steam"
                            : p.displayGpu + " reconocida · PanVK/Kbase público experimental; todavía sin paquete glibc integrado";
                case BRINGUP:
                    return p.displayGpu + " reconocida · soporte Kbase todavía en fase de bring-up";
                default:
                    break;
            }
        }
        if (probe.getFrontend() == MaliKbaseProbe.Frontend.JM) {
            return "Kbase JM detectado · no usar drivers CSF; soporte guest todavía experimental";
        }
        if (probe.getFrontend() == MaliKbaseProbe.Frontend.CSF) {
            return "Kbase CSF detectado · Product ID " + probe.getProductCodeHex()
                    + " todavía sin perfil glibc calificado en DroidDeck";
        }
        return "Kbase detectado · frontend sin clasificar";
    }

    public static boolean isPinnedG57(Profile profile) {
        return profile != null && G57_V9_JM.id.equals(profile.id);
    }

    /** Trusted metadata for the one immutable G57/JM glibc asset accepted by Automatic. */
    public static JSONObject trustedPinnedG57Metadata(String tag) throws JSONException {
        if (!G57_RELEASE_TAG.equals(tag)) return null;
        JSONObject out = new JSONObject();
        out.put("schemaVersion", 4);
        out.put("source", G57_RELEASE_SOURCE_LABEL);
        out.put("sourceRepo", G57_RELEASE_REPO);
        out.put("sourceTag", G57_RELEASE_TAG);
        out.put("sourceSha256", G57_RELEASE_SHA256);
        out.put("maliProfile", G57_V9_JM.id);
        out.put("maliPanArch", G57_V9_JM.panArch);
        out.put("guestBackend", "mali-kbase");
        out.put("maliKbaseFrontend", "jm");
        out.put("maliKbaseUapiMajor", 11);
        JSONArray products = new JSONArray();
        products.put("0x9091");
        products.put("0x9093");
        out.put("maliProductIds", products);
        out.put("testedDevice", G57_V9_JM.testedDevice);
        out.put("driverVersion", "1.0.0-glibc-async");
        out.put("qualification", "experimental");
        out.put("minGlibc", "2.38");
        return out;
    }

    /** Profile encoded by an immutable PanVK-Kbase release tag, or null when not qualified here. */
    public static Profile fromReleaseTag(String tag) {
        if (tag == null) return null;
        String t = tag.trim().toLowerCase(Locale.US);
        // Only sources with an integrated glibc release route are accepted here.
        if (t.startsWith(G615_V11_CSF.id + "-v")) return G615_V11_CSF;
        return null;
    }

    /**
     * Label for a downloadable glibc/PRoot release asset. null means "do not offer this asset".
     * Android/bionic adpkg files are deliberately excluded.
     */
    public static String releaseAssetLabel(String name, String tag) {
        Profile p = fromReleaseTag(tag);
        if (p == null || !p.glibcReleaseIntegrated || name == null) return null;
        String n = name.toLowerCase(Locale.US);
        if (!n.endsWith(".zip")) return null;
        // The producer uses -EMULATOR.zip for the ARM64 glibc runtime consumer. Keep a glibc
        // spelling fallback for future producer naming without accidentally accepting ADPKG.
        if (!(n.contains("emulator") || n.contains("glibc"))) return null;
        if (n.contains("adpkg")) return null;
        return p.displayGpu + " · Pan v" + p.panArch + " · "
                + p.frontend.toUpperCase(Locale.US) + " · Kbase/glibc";
    }

    /** Metadata that may be injected only after a checksum-verified asset from RELEASE_REPO. */
    public static JSONObject trustedReleaseMetadata(String tag) throws JSONException {
        Profile p = fromReleaseTag(tag);
        if (p == null) return null;
        JSONObject out = new JSONObject();
        out.put("schemaVersion", 4);
        out.put("source", RELEASE_SOURCE_LABEL);
        out.put("sourceRepo", RELEASE_REPO);
        out.put("sourceTag", tag);
        out.put("maliProfile", p.id);
        out.put("maliPanArch", p.panArch);
        out.put("guestBackend", "mali-kbase");
        out.put("maliKbaseFrontend", p.frontend);
        if (p.uapiMajor >= 0) out.put("maliKbaseUapiMajor", p.uapiMajor);
        if (p.uapiMinMinor >= 0) out.put("maliKbaseMinUapiMinor", p.uapiMinMinor);
        if (p.uapiMaxMinor >= 0) out.put("maliKbaseMaxUapiMinor", p.uapiMaxMinor);
        JSONArray products = new JSONArray();
        for (long id : p.productIds) products.put(String.format(Locale.US, "0x%04x", id));
        out.put("maliProductIds", products);
        out.put("testedDevice", p.testedDevice);
        String version = releaseVersion(tag);
        if (!version.isEmpty()) out.put("driverVersion", version);
        String q = qualification(version);
        if (!q.isEmpty()) out.put("qualification", q);
        return out;
    }

    public static String releaseVersion(String tag) {
        Profile p = fromReleaseTag(tag);
        if (p == null) return "";
        String prefix = p.id + "-v";
        return tag.substring(prefix.length());
    }

    private static String qualification(String version) {
        String v = version == null ? "" : version.toLowerCase(Locale.US);
        if (v.contains("alpha")) return "alpha";
        if (v.contains("beta")) return "beta";
        if (v.contains("-rc") || v.startsWith("rc")) return "rc";
        return v.isEmpty() ? "" : "release";
    }
}
