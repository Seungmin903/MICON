package com.micon.backend.preference;

import static org.assertj.core.api.Assertions.assertThat;
import com.micon.backend.domain.preference.dto.PreferenceResponse.Genre;
import com.micon.backend.domain.preference.repository.PreferenceCatalogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:preference-catalog;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
@AutoConfigureMockMvc
class PreferenceCatalogMigrationTest {
    @Autowired PreferenceCatalogRepository catalog;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;

    @Test void hostedMinKyungHoonPortraitIsPackagedAsJpeg() throws Exception {
        var resource = new ClassPathResource("static/artist-portraits/min-kyung-hoon.jpg");
        assertThat(resource.exists()).isTrue();
        try (var input = resource.getInputStream()) {
            assertThat(input.readNBytes(2)).containsExactly((byte) 0xFF, (byte) 0xD8);
        }
    }

    @Test void hostedMinKyungHoonPortraitIsPublicAndServedAsJpeg() throws Exception {
        mockMvc.perform(get("/artist-portraits/min-kyung-hoon.jpg"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/jpeg"));
    }

    @Test void freshDatabaseOffersTenUniqueOrderedChoicesWithoutSeedingFakeSingers() {
        var genres = catalog.findGenres();
        assertThat(genres).extracting(Genre::name).containsExactly(
            "발라드", "댄스", "R&B/소울", "록/메탈", "힙합", "포크/어쿠스틱", "OST", "영어곡", "일본곡", "트로트");
        assertThat(genres).extracting(Genre::code).doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM artist_portrait", Integer.class)).isEqualTo(89);
        assertThat(jdbc.queryForObject("SELECT name FROM genre WHERE normalized_name='kpop-adult'", String.class)).isEqualTo("성인가요");
        assertThat(jdbc.queryForObject("SELECT name FROM genre WHERE normalized_name='global-pop'", String.class)).isEqualTo("팝");
        assertThat(jdbc.queryForObject("SELECT name FROM genre WHERE normalized_name='etc-jpop'", String.class)).isEqualTo("J-POP");
    }

    @Test @Transactional void curatedPortraitOverridesLegacyImageForRoyKimAndIU() {
        jdbc.update("INSERT INTO singer (singer_id, name, normalized_name, image_url) VALUES (90001, 'IU', 'iu', 'https://cdn.example/wrong-person.jpg')");
        jdbc.update("INSERT INTO singer (singer_id, name, normalized_name, image_url) VALUES (90002, 'Roy Kim', 'roy kim', 'https://cdn.example/old-portrait.jpg')");

        var iu = catalog.findArtistsByIds(java.util.List.of(90001L)).get(0);
        assertThat(iu.name()).isEqualTo("아이유");
        assertThat(iu.imageUrl()).isEqualTo("https://image.bugsm.co.kr/artist/images/1200/800491/80049126.jpg");
        assertThat(iu.imageSourceUrl()).isEqualTo("https://music.bugs.co.kr/artist/80049126");
        assertThat(iu.imageAttribution()).isEqualTo("Bugs artist page; photo author not specified");
        assertThat(iu.imageLicense()).isEqualTo("UNVERIFIED");

        var royKim = catalog.findArtistsByIds(java.util.List.of(90002L)).get(0);
        assertThat(royKim.name()).isEqualTo("로이킴");
        assertThat(royKim.imageUrl()).isEqualTo("https://image.bugsm.co.kr/artist/images/1200/801377/80137715.jpg");
        assertThat(royKim.imageSourceUrl()).isEqualTo("https://music.bugs.co.kr/artist/80137715");
        assertThat(royKim.imageAttribution()).isEqualTo("Bugs artist page; photo author not specified");
        assertThat(royKim.imageLicense()).isEqualTo("UNVERIFIED");
    }

    @Test @Transactional void replacesIncorrectJustinBieberPortraitWithVerifiedArtistImage() {
        jdbc.update("INSERT INTO singer (singer_id, name, normalized_name, image_url) VALUES (90006, 'Justin Bieber', 'justin bieber', 'https://cdn.example/wrong-person.jpg')");

        var justinBieber = catalog.findArtistsByIds(java.util.List.of(90006L)).get(0);

        assertThat(justinBieber.imageUrl()).isEqualTo("https://image.bugsm.co.kr/artist/images/1200/800577/80057730.jpg");
        assertThat(justinBieber.imageSourceUrl()).isEqualTo("https://music.bugs.co.kr/artist/80057730");
        assertThat(justinBieber.imageAttribution()).isEqualTo("Bugs artist page; photo author not specified");
        assertThat(justinBieber.imageLicense()).isEqualTo("UNVERIFIED");
    }

    @Test @Transactional void verifiedKimBumSooAndBol4PortraitsOverrideLegacyImages() {
        jdbc.update("INSERT INTO singer (singer_id, name, normalized_name, image_url) VALUES (90004, '김범수', '김범수', 'https://cdn.example/wrong-person.jpg')");
        jdbc.update("INSERT INTO singer (singer_id, name, normalized_name, image_url) VALUES (90005, 'BOL4', 'bol4', 'https://cdn.example/old-group-photo.jpg')");

        var kim = catalog.findArtistsByIds(java.util.List.of(90004L)).get(0);
        assertThat(kim.name()).isEqualTo("김범수");
        assertThat(kim.imageUrl()).isEqualTo("https://image.bugsm.co.kr/artist/images/1200/68/6886.jpg");
        assertThat(kim.imageSourceUrl()).isEqualTo("https://music.bugs.co.kr/artist/6886");
        assertThat(kim.imageAttribution()).isEqualTo("Bugs artist page; photo author not specified");
        assertThat(kim.imageLicense()).isEqualTo("UNVERIFIED");

        var bol4 = catalog.findArtistsByIds(java.util.List.of(90005L)).get(0);
        assertThat(bol4.name()).isEqualTo("볼빨간사춘기");
        assertThat(bol4.imageUrl()).isEqualTo("https://image.bugsm.co.kr/artist/images/1200/802106/80210668.jpg");
        assertThat(bol4.imageSourceUrl()).isEqualTo("https://music.bugs.co.kr/artist/80210668");
        assertThat(bol4.imageAttribution()).isEqualTo("Bugs artist page; photo author not specified");
        assertThat(bol4.imageLicense()).isEqualTo("UNVERIFIED");
    }

    @Test @Transactional void verifiedMinKyungHoonPortraitOverridesLegacyImage() {
        jdbc.update("INSERT INTO singer (singer_id, name, normalized_name, image_url) VALUES (90007, '민경훈', '민경훈', 'https://cdn.example/old-portrait.jpg')");

        var minKyungHoon = catalog.findArtistsByIds(java.util.List.of(90007L)).get(0);

        assertThat(minKyungHoon.name()).isEqualTo("민경훈");
        assertThat(minKyungHoon.imageUrl()).isEqualTo("https://j15b110.p.ssafy.io/api/v1/artist-portraits/min-kyung-hoon.jpg");
        assertThat(minKyungHoon.imageSourceUrl()).isEqualTo("https://commons.wikimedia.org/wiki/File:Min_Kyung-Hoon.jpg");
        assertThat(minKyungHoon.imageAttribution()).isEqualTo("Korea.net / Korean Culture and Information Service (Photographer: Jeon Han)");
        assertThat(minKyungHoon.imageLicense()).isEqualTo("CC BY-SA 2.0");
    }

    @Test @Transactional void legacyImageRemainsFallbackWhenArtistHasNoCuratedPortrait() {
        jdbc.update("INSERT INTO singer (singer_id, name, normalized_name, image_url) VALUES (90003, 'Uncatalogued Singer', 'uncatalogued-test-singer', 'https://cdn.example/legacy.jpg')");

        var artist = catalog.findArtistsByIds(java.util.List.of(90003L)).get(0);

        assertThat(artist.imageUrl()).isEqualTo("https://cdn.example/legacy.jpg");
        assertThat(artist.imageSourceUrl()).isNull();
        assertThat(artist.imageAttribution()).isNull();
        assertThat(artist.imageLicense()).isNull();
    }
}
