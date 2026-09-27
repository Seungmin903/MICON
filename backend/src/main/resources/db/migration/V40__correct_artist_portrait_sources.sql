-- Replace the incorrectly selected/stale portraits with identity- and license-verified
-- Wikimedia Commons images. Keep this correction in a new migration because V14 has
-- already been applied to deployed databases.
UPDATE artist_portrait
SET display_name = '로이킴',
    search_names = '로이킴 / Roy Kim',
    image_url = 'https://upload.wikimedia.org/wikipedia/commons/f/f8/KCON_2015_Roy_Kim_%28cropped%29.jpg',
    source_url = 'https://commons.wikimedia.org/wiki/File:KCON_2015_Roy_Kim_(cropped).jpg',
    attribution = 'mduangdara',
    license = 'CC BY-SA 2.0',
    license_url = 'https://creativecommons.org/licenses/by-sa/2.0'
WHERE normalized_name IN ('로이킴', 'roy kim');

UPDATE artist_portrait
SET display_name = '아이유',
    search_names = '아이유 / IU',
    image_url = 'https://upload.wikimedia.org/wikipedia/commons/2/24/IU_singer.jpg',
    source_url = 'https://commons.wikimedia.org/wiki/File:IU_singer.jpg',
    attribution = 'Marie Claire Korea',
    license = 'CC BY 3.0',
    license_url = 'https://creativecommons.org/licenses/by/3.0'
WHERE normalized_name IN ('아이유', 'iu');

UPDATE artist_portrait
SET display_name = '볼빨간사춘기',
    search_names = '볼빨간사춘기 / BOL4',
    image_url = 'https://upload.wikimedia.org/wikipedia/commons/8/86/180228_Ahn_Ji-young_at_the_Japan_Tokyo_Showcase.jpg',
    source_url = 'https://commons.wikimedia.org/wiki/File:180228_Ahn_Ji-young_at_the_Japan_Tokyo_Showcase.jpg',
    attribution = 'Seono_',
    license = 'CC BY-SA 4.0',
    license_url = 'https://creativecommons.org/licenses/by-sa/4.0'
WHERE normalized_name IN ('볼빨간사춘기', 'bol4');

INSERT INTO artist_portrait
    (normalized_name, display_name, search_names, image_url, source_url, attribution, license, license_url)
SELECT '김범수', '김범수', '김범수 / Kim Bum-soo',
       'https://upload.wikimedia.org/wikipedia/commons/8/86/KIm_Bum-Soo.jpg',
       'https://commons.wikimedia.org/wiki/File:KIm_Bum-Soo.jpg',
       '여니수니', 'CC BY 2.0 kr', 'https://creativecommons.org/licenses/by/2.0/kr/deed.en'
WHERE NOT EXISTS (SELECT 1 FROM artist_portrait WHERE normalized_name = '김범수');

INSERT INTO artist_portrait
    (normalized_name, display_name, search_names, image_url, source_url, attribution, license, license_url)
SELECT 'kim bum-soo', '김범수', '김범수 / Kim Bum-soo',
       'https://upload.wikimedia.org/wikipedia/commons/8/86/KIm_Bum-Soo.jpg',
       'https://commons.wikimedia.org/wiki/File:KIm_Bum-Soo.jpg',
       '여니수니', 'CC BY 2.0 kr', 'https://creativecommons.org/licenses/by/2.0/kr/deed.en'
WHERE NOT EXISTS (SELECT 1 FROM artist_portrait WHERE normalized_name = 'kim bum-soo');
