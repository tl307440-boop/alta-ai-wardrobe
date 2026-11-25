package com.wardrobe.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wardrobe.model.dto.VisionDto;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BoundingBoxNormalizationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static VisionDto.BoundingBox norm(String json) throws Exception {
        JsonNode node = MAPPER.readTree(json);
        return VisionRecognitionService.normalizeBoundingBox(node);
    }

    @Test
    void keepsFractionBoxes() throws Exception {
        VisionDto.BoundingBox b = norm("[0.2, 0.1, 0.6, 0.35]");
        assertThat(b).isNotNull();
        assertThat(b.getX()).isEqualTo(0.2, within(1e-9));
        assertThat(b.getY()).isEqualTo(0.1, within(1e-9));
        assertThat(b.getW()).isEqualTo(0.6, within(1e-9));
        assertThat(b.getH()).isEqualTo(0.35, within(1e-9));
    }

    @Test
    void scalesPercentAndThousandGrids() throws Exception {
        VisionDto.BoundingBox pct = norm("[30, 50, 40, 45]");
        assertThat(pct.getX()).isEqualTo(0.3, within(1e-9));
        assertThat(pct.getH()).isEqualTo(0.45, within(1e-9));

        VisionDto.BoundingBox grid = norm("{\"x\": 250, \"y\": 100, \"width\": 500, \"height\": 600}");
        assertThat(grid.getX()).isEqualTo(0.25, within(1e-9));
        assertThat(grid.getW()).isEqualTo(0.5, within(1e-9));
        assertThat(grid.getH()).isEqualTo(0.6, within(1e-9));
    }

    @Test
    void convertsCornerBoxesThatOverflowAsWidthHeight() throws Exception {
        // [x1, y1, x2, y2] = [0.5, 0.4, 0.9, 0.95] -> x + w would be 1.4 so it must be treated as corners.
        VisionDto.BoundingBox b = norm("[0.5, 0.4, 0.9, 0.95]");
        assertThat(b.getX()).isEqualTo(0.5, within(1e-9));
        assertThat(b.getW()).isEqualTo(0.4, within(1e-9));
        assertThat(b.getH()).isEqualTo(0.55, within(1e-9));
    }

    @Test
    void clampsBoxesPokingOutsideTheImage() throws Exception {
        VisionDto.BoundingBox b = norm("[0.8, 0.8, 0.3, 0.3]");
        assertThat(b).isNotNull();
        assertThat(b.getX() + b.getW()).isLessThanOrEqualTo(1.0 + 1e-9);
        assertThat(b.getY() + b.getH()).isLessThanOrEqualTo(1.0 + 1e-9);
    }

    @Test
    void rejectsUnusableBoxes() throws Exception {
        assertThat(norm("null")).isNull();
        assertThat(norm("\"unknown\"")).isNull();
        assertThat(norm("[0.1, 0.2, 0.3]")).isNull();
        assertThat(norm("[0.1, 0.2, 0, 0.3]")).isNull();
        assertThat(norm("[-0.1, 0.2, 0.3, 0.3]")).isNull();
        assertThat(norm("[0.1, 0.1, 0.01, 0.5]")).isNull();
        assertThat(norm("[100, 200, 5000, 40]")).isNull();
        assertThat(VisionRecognitionService.normalizeBoundingBox(null)).isNull();
    }
}
