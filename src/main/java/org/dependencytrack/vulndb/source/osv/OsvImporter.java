package org.dependencytrack.vulndb.source.osv;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import io.github.nscuro.versatile.Vers;
import io.github.nscuro.versatile.VersUtils;
import org.dependencytrack.vulndb.api.Database;
import org.dependencytrack.vulndb.api.Importer;
import org.dependencytrack.vulndb.api.MatchingCriteria;
import org.dependencytrack.vulndb.api.Source;
import org.dependencytrack.vulndb.api.Vulnerability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static java.util.function.Predicate.not;

public final class OsvImporter implements Importer {

    private static final Logger LOGGER = LoggerFactory.getLogger(OsvImporter.class);
    private static final Set<String> ENABLED_ECOSYSTEMS = Set.of(
            "Debian",
            "Maven",
            "NuGet",
            "Packagist",
            "Pub",
            "PyPI",
            "Red Hat",
            "Rocky Linux",
            "RubyGems",
            "SUSE",
            "Ubuntu",
            "Wolfi",
            "crates.io",
            "npm",
            "openSUSE");

    private Database database;
    private HttpClient httpClient;
    private ObjectMapper objectMapper;

    @Override
    public Source source() {
        return new Source("OSV", "", "https://osv.dev/");
    }

    @Override
    public void init(final Database database) {
        this.database = database;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
    }

    @Override
    public void runImport() throws Exception {
        final List<String> availableEcosystems = getAvailableEcosystems();
        LOGGER.info("Available ecosystems: {}", availableEcosystems);

        for (final String ecosystem : availableEcosystems) {
            if (ENABLED_ECOSYSTEMS.contains(ecosystem)) {
                LOGGER.info("Skipping ecosystem {}", ecosystem);
                continue;
            }

            LOGGER.info("Downloading archive of ecosystem {}", ecosystem);
            final Path ecosystemArchivePath = downloadEcosystemArchive(ecosystem);

            extractEcosystemArchive(ecosystemArchivePath, this::processAdvisory);
        }
    }

    private List<String> getAvailableEcosystems() throws InterruptedException, IOException {
        final HttpResponse<Stream<String>> response = httpClient.send(
                HttpRequest.newBuilder(URI.create(
                                "https://osv-vulnerabilities.storage.googleapis.com/ecosystems.txt"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            throw new IOException("Unexpected response code: " + response.statusCode());
        }

        return response.body()
                .map(String::trim)
                .filter(not(String::isEmpty))
                .sorted()
                .collect(Collectors.toList());
    }

    private Path downloadEcosystemArchive(final String ecosystem) throws InterruptedException, IOException {
        final Path tempFile = Files.createTempFile(null, ".zip");
        tempFile.toFile().deleteOnExit();

        final String encodedEcosystem = URLEncoder.encode(ecosystem, StandardCharsets.UTF_8).replace("+", "%20");

        final HttpResponse<Path> response = httpClient.send(
                HttpRequest.newBuilder(URI.create(
                                "https://osv-vulnerabilities.storage.googleapis.com/%s/all.zip".formatted(encodedEcosystem)))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofFile(tempFile, StandardOpenOption.WRITE));
        if (response.statusCode() != 200) {
            throw new IOException("Unexpected response code: " + response.statusCode());
        }

        return response.body();
    }

    private void extractEcosystemArchive(
            final Path archivePath,
            final Consumer<OsvAdvisory> advisoryConsumer) throws IOException {
        try (final var zipFile = new ZipFile(archivePath.toFile())) {
            final Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
            while (zipEntries.hasMoreElements()) {
                final ZipEntry entry = zipEntries.nextElement();

                try (final InputStream entryInputStream = zipFile.getInputStream(entry)) {
                    final var advisory = objectMapper.readValue(entryInputStream, OsvAdvisory.class);
                    advisoryConsumer.accept(advisory);
                }
            }
        }
    }

    private void processAdvisory(final OsvAdvisory advisory) {
        final var matchingCriteriaList = new ArrayList<MatchingCriteria>();
        if (advisory.affected() != null) {
            for (final OsvAdvisory.Affected affected : advisory.affected()) {
                if (affected.pkg() == null || affected.pkg().purl() == null) {
                    LOGGER.debug("No package information; Skipping  {}", affected);
                    continue;
                }

                final PackageURL purl;
                try {
                    purl = new PackageURL(affected.pkg().purl());
                } catch (MalformedPackageURLException e) {
                    LOGGER.warn("Encountered invalid PURL; Skipping {}", affected);
                    continue;
                }

                if (affected.ranges() != null) {
                    for (final OsvAdvisory.Range range : affected.ranges()) {
                        try {
                            final Vers vers = VersUtils.versFromOsvRange(
                                    range.type(),
                                    affected.pkg().ecosystem(),
                                    range.genericEvents(),
                                    range.databaseSpecific());

                            matchingCriteriaList.add(new MatchingCriteria(
                                    null,
                                    purl,
                                    vers,
                                    null,
                                    null));
                        } catch (RuntimeException e) {
                            LOGGER.warn("Failed to build vers for {}", range, e);
                        }
                    }
                }
            }
        }

        final var vuln = new Vulnerability(
                advisory.id(),
                advisory.aliases(),
                advisory.details(),
                null,
                null,
                null,
                !matchingCriteriaList.isEmpty() ? matchingCriteriaList : null,
                null,
                advisory.published(),
                advisory.modified(),
                advisory.withdrawn());

        database.storeVulnerabilities(List.of(vuln));
    }

}
