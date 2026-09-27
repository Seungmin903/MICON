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

    @Test @Transactional void curatedPortraitOverridesLegacyImageForRoyKimAndIU() {
        jdbc.update("INSERT INTO singer (singer_id, name, normalized_name, image_url) VALUES (90001, 'IU', 'iu', 'https://cdn.example/wrong-person.jpg')");
        jdbc.update("INSERT INTO singer (singer_id, name, normalized_name, image_url) VALUES (90002, 'Roy Kim', 'roy kim', 'https://cdn.example/old-portrait.jpg')");

        var iu = catalog.findArtistsByIds(java.util.List.of(90001L)).get(0);
        assertThat(iu.name()).isEqualTo("아이유");
        assertThat(iu.imageUrl()).contains("IU_at_Blue_Dragon_Series_Awards");
        assertThat(iu.imageSourceUrl()).contains("commons.wikimedia.org/wiki/File:IU_at_Blue_Dragon");
        assertThat(iu.imageAttribution()).isEqualTo("티비텐 TV10");
        assertThat(iu.imageLicense()).isEqualTo("CC BY 4.0");

        var royKim = catalog.findArtistsByIds(java.util.List.of(90002L)).get(0);
        assertThat(royKim.name()).isEqualTo("로이킴");
        assertThat(royKim.imageUrl()).contains("180531_%EB%A1%9C%EC%9D%B4%ED%82%B4.jpg");
        assertThat(royKim.imageAttribution()).isEqualTo("dispatchsns");
        assertThat(royKim.imageLicense()).isEqualTo("CC BY 3.0");
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
