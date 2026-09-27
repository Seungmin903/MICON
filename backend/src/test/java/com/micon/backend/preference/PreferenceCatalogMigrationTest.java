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
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM artist_portrait", Integer.class)).isEqualTo(82);
        assertThat(jdbc.queryForObject("SELECT name FROM genre WHERE normalized_name='kpop-adult'", String.class)).isEqualTo("성인가요");
        assertThat(jdbc.queryForObject("SELECT name FROM genre WHERE normalized_name='global-pop'", String.class)).isEqualTo("팝");
        assertThat(jdbc.queryForObject("SELECT name FROM genre WHERE normalized_name='etc-jpop'", String.class)).isEqualTo("J-POP");
    }

    @Test @Transactional void curatedPortraitOverridesLegacyImageForRoyKimAndIU() {
        jdbc.update("INSERT INTO singer (singer_id, name, normalized_name, image_url) VALUES (90001, 'IU', 'iu', 'https://cdn.example/wrong-person.jpg')");
        jdbc.update("INSERT INTO singer (singer_id, name, normalized_name, image_url) VALUES (90002, 'Roy Kim', 'roy kim', 'https://cdn.example/old-portrait.jpg')");

        var iu = catalog.findArtistsByIds(java.util.List.of(90001L)).get(0);
        assertThat(iu.name()).isEqualTo("아이유");
        assertThat(iu.imageUrl()).isEqualTo("https://upload.wikimedia.org/wikipedia/commons/2/24/IU_singer.jpg");
        assertThat(iu.imageSourceUrl()).isEqualTo("https://commons.wikimedia.org/wiki/File:IU_singer.jpg");
        assertThat(iu.imageAttribution()).isEqualTo("Marie Claire Korea");
        assertThat(iu.imageLicense()).isEqualTo("CC BY 3.0");

        var royKim = catalog.findArtistsByIds(java.util.List.of(90002L)).get(0);
        assertThat(royKim.name()).isEqualTo("로이킴");
        assertThat(royKim.imageUrl()).isEqualTo("https://upload.wikimedia.org/wikipedia/commons/f/f8/KCON_2015_Roy_Kim_%28cropped%29.jpg");
        assertThat(royKim.imageSourceUrl()).isEqualTo("https://commons.wikimedia.org/wiki/File:KCON_2015_Roy_Kim_(cropped).jpg");
        assertThat(royKim.imageAttribution()).isEqualTo("mduangdara");
        assertThat(royKim.imageLicense()).isEqualTo("CC BY-SA 2.0");
    }

    @Test @Transactional void replacesIncorrectJustinBieberPortraitWithVerifiedArtistImage() {
        jdbc.update("INSERT INTO singer (singer_id, name, normalized_name, image_url) VALUES (90006, 'Justin Bieber', 'justin bieber', 'https://cdn.example/wrong-person.jpg')");

        var justinBieber = catalog.findArtistsByIds(java.util.List.of(90006L)).get(0);

        assertThat(justinBieber.imageUrl()).isEqualTo("https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d8/Justin_Bieber_in_concert_crop.jpg/500px-Justin_Bieber_in_concert_crop.jpg");
        assertThat(justinBieber.imageSourceUrl()).isEqualTo("https://commons.wikimedia.org/wiki/File:Justin_Bieber_in_concert_crop.jpg");
        assertThat(justinBieber.imageAttribution()).isEqualTo("Moxy (original photo by Heather Sokol)");
        assertThat(justinBieber.imageLicense()).isEqualTo("CC BY-SA 2.0");
    }

    @Test @Transactional void verifiedKimBumSooAndBol4PortraitsOverrideLegacyImages() {
        jdbc.update("INSERT INTO singer (singer_id, name, normalized_name, image_url) VALUES (90004, '김범수', '김범수', 'https://cdn.example/wrong-person.jpg')");
        jdbc.update("INSERT INTO singer (singer_id, name, normalized_name, image_url) VALUES (90005, 'BOL4', 'bol4', 'https://cdn.example/old-group-photo.jpg')");

        var kim = catalog.findArtistsByIds(java.util.List.of(90004L)).get(0);
        assertThat(kim.name()).isEqualTo("김범수");
        assertThat(kim.imageUrl()).isEqualTo("https://upload.wikimedia.org/wikipedia/commons/8/86/KIm_Bum-Soo.jpg");
        assertThat(kim.imageSourceUrl()).isEqualTo("https://commons.wikimedia.org/wiki/File:KIm_Bum-Soo.jpg");
        assertThat(kim.imageAttribution()).isEqualTo("여니수니");
        assertThat(kim.imageLicense()).isEqualTo("CC BY 2.0 kr");

        var bol4 = catalog.findArtistsByIds(java.util.List.of(90005L)).get(0);
        assertThat(bol4.name()).isEqualTo("볼빨간사춘기");
        assertThat(bol4.imageUrl()).isEqualTo("https://upload.wikimedia.org/wikipedia/commons/8/86/180228_Ahn_Ji-young_at_the_Japan_Tokyo_Showcase.jpg");
        assertThat(bol4.imageSourceUrl()).isEqualTo("https://commons.wikimedia.org/wiki/File:180228_Ahn_Ji-young_at_the_Japan_Tokyo_Showcase.jpg");
        assertThat(bol4.imageAttribution()).isEqualTo("Seono_");
        assertThat(bol4.imageLicense()).isEqualTo("CC BY-SA 4.0");
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
