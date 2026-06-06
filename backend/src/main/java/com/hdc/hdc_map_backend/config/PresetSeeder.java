package com.hdc.hdc_map_backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hdc.hdc_map_backend.entity.taxBenefits.DealConduit;
import com.hdc.hdc_map_backend.repository.taxBenefits.DealConduitRepository;
import com.hdc.hdc_map_backend.service.taxBenefits.DealConduitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Objects;

@Component
@Profile("dev")
public class PresetSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PresetSeeder.class);
    private static final String SEED_PATTERN = "classpath:seed/presets/*.json";

    private final DealConduitService dealConduitService;
    private final DealConduitRepository dealConduitRepository;
    private final ObjectMapper objectMapper;

    public PresetSeeder(DealConduitService dealConduitService,
                        DealConduitRepository dealConduitRepository,
                        ObjectMapper objectMapper) {
        this.dealConduitService = dealConduitService;
        this.dealConduitRepository = dealConduitRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(String... args) {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(SEED_PATTERN);
        } catch (Exception e) {
            log.warn("PresetSeeder: unable to scan {} — {}", SEED_PATTERN, e.getMessage());
            return;
        }

        for (Resource resource : resources) {
            try (InputStream in = resource.getInputStream()) {
                DealConduit conduit = objectMapper.readValue(in, DealConduit.class);
                String dealName = conduit.getPortalSettings() != null
                        ? conduit.getPortalSettings().getDealName()
                        : null;
                if (dealName == null || dealName.isBlank()) {
                    log.warn("PresetSeeder: skipping {} — no dealName in payload", resource.getFilename());
                    continue;
                }

                boolean exists = dealConduitRepository.findByIsPresetTrueOrderByCreatedAtDesc().stream()
                        .map(DealConduit::getPortalSettings)
                        .filter(Objects::nonNull)
                        .map(ps -> ps.getDealName())
                        .anyMatch(dealName::equals);

                if (exists) {
                    log.info("PresetSeeder: '{}' already present, skipping", dealName);
                    continue;
                }

                dealConduitService.savePreset(conduit);
                log.info("PresetSeeder: loaded preset '{}' from {}", dealName, resource.getFilename());
            } catch (Exception e) {
                log.error("PresetSeeder: failed to load {} — {}", resource.getFilename(), e.getMessage(), e);
            }
        }
    }
}
