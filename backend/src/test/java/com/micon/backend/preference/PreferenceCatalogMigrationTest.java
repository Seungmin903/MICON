package com.micon.backend.preference;

import static org.assertj.core.api.Assertions.assertThat;
import com.micon.backend.domain.preference.dto.PreferenceResponse.Genre;
import com.micon.backend.domain.preference.repository.PreferenceCatalogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:preference-catalog;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
class PreferenceCatalogMigrationTest {
    @Autowired PreferenceCatalogRepository catalog;
    @Autowired JdbcTemplate jdbc;

    @Test void freshDatabaseOffersTenUniqueOrderedChoicesWithoutSeedingFakeSingers() {
        var genres = catalog.findGenres();
        assertThat(genres).extracting(Genre::name).containsExactly(
            "발라드", "댄스", "R&B/소울", "록/메탈", "힙합", "포크/어쿠스틱", "OST", "영어곡", "일본곡", "트로트");
        assertThat(genres).extracting(Genre::code).doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM artist_portrait", Integer.class)).isEqualTo(54);
        assertThat(jdbc.queryForObject("SELECT name FROM genre WHERE normalized_name='kpop-adult'", String.class)).isEqualTo("성인가요");
        assertThat(jdbc.queryForObject("SELECT name FROM genre WHERE normalized_name='global-pop'", String.class)).isEqualTo("팝");
        assertThat(jdbc.queryForObject("SELECT name FROM genre WHERE normalized_name='etc-jpop'", String.class)).isEqualTo("J-POP");
    }

    @Test @Transactional void singerImageTakesPriorityOverRateLimitedPortraitOverride() {
        jdbc.update("INSERT INTO singer (singer_id, name, normalized_name, image_url) VALUES (90001, 'IU', 'iu', 'https://cdn.example/iu.jpg')");

        var artist = catalog.findArtistsByIds(java.util.List.of(90001L)).get(0);

        assertThat(artist.imageUrl()).isEqualTo("https://cdn.example/iu.jpg");
        assertThat(artist.imageSourceUrl()).isNull();
        assertThat(artist.imageAttribution()).isNull();
        assertThat(artist.imageLicense()).isNull();

        jdbc.update("UPDATE singer SET image_url=NULL WHERE singer_id=90001");
        var portraitFallback = catalog.findArtistsByIds(java.util.List.of(90001L)).get(0);
        assertThat(portraitFallback.imageUrl()).contains("wikimedia.org");
        assertThat(portraitFallback.imageSourceUrl()).contains("commons.wikimedia.org");
    }
}
