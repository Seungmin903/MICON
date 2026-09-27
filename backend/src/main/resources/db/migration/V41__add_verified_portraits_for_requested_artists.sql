-- Additional identity- and license-checked portraits requested for the singer catalog.
-- Use Commons' file redirect so the original media URL remains maintained by Commons.
UPDATE artist_portrait SET search_names = CONCAT(search_names, ' / Mrs green')
WHERE normalized_name = 'mrs. green apple';
UPDATE artist_portrait SET search_names = CONCAT(search_names, ' / g dragon')
WHERE normalized_name = 'g-dragon';
-- The previous Justin Bieber URL resolves to a White House photo captioned only
-- as President Trump and the First Lady. Replace it with a Commons file explicitly
-- depicting Bieber and carrying a reviewed CC BY-SA 2.0 license.
UPDATE artist_portrait
SET display_name = 'Justin Bieber',
    search_names = 'Justin Bieber',
    image_url = 'https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d8/Justin_Bieber_in_concert_crop.jpg/500px-Justin_Bieber_in_concert_crop.jpg',
    source_url = 'https://commons.wikimedia.org/wiki/File:Justin_Bieber_in_concert_crop.jpg',
    attribution = 'Moxy (original photo by Heather Sokol)',
    license = 'CC BY-SA 2.0',
    license_url = 'https://creativecommons.org/licenses/by-sa/2.0'
WHERE normalized_name = 'justin bieber';

INSERT INTO artist_portrait
    (normalized_name, display_name, search_names, image_url, source_url, attribution, license, license_url)
SELECT 'g dragon', display_name, CONCAT(search_names, ' / g dragon'), image_url, source_url, attribution, license, license_url
FROM artist_portrait WHERE normalized_name = 'g-dragon'
  AND NOT EXISTS (SELECT 1 FROM artist_portrait alias WHERE alias.normalized_name = 'g dragon');
INSERT INTO artist_portrait
    (normalized_name, display_name, search_names, image_url, source_url, attribution, license, license_url)
SELECT 'mrs green apple', display_name, CONCAT(search_names, ' / Mrs green'), image_url, source_url, attribution, license, license_url
FROM artist_portrait WHERE normalized_name = 'mrs. green apple'
  AND NOT EXISTS (SELECT 1 FROM artist_portrait alias WHERE alias.normalized_name = 'mrs green apple');

INSERT INTO artist_portrait
    (normalized_name, display_name, search_names, image_url, source_url, attribution, license, license_url)
SELECT '수지', '수지', '수지 / Bae Suzy',
       'https://thumb.wikimedia.org/wikipedia/commons/thumb/9/9a/Bae_Suzy_at_Incheon_Airport_on_July_18%2C_2017_%284%29.jpg/500px-Bae_Suzy_at_Incheon_Airport_on_July_18%2C_2017_%284%29.jpg',
       'https://commons.wikimedia.org/wiki/File:Bae_Suzy_at_Incheon_Airport_on_July_18,_2017_(4).jpg',
       'SHAQ Photo', 'CC BY 4.0', 'https://creativecommons.org/licenses/by/4.0'
WHERE NOT EXISTS (SELECT 1 FROM artist_portrait WHERE normalized_name = '수지');

INSERT INTO artist_portrait
    (normalized_name, display_name, search_names, image_url, source_url, attribution, license, license_url)
SELECT 'bae suzy', '수지', '수지 / Bae Suzy',
       'https://thumb.wikimedia.org/wikipedia/commons/thumb/9/9a/Bae_Suzy_at_Incheon_Airport_on_July_18%2C_2017_%284%29.jpg/500px-Bae_Suzy_at_Incheon_Airport_on_July_18%2C_2017_%284%29.jpg',
       'https://commons.wikimedia.org/wiki/File:Bae_Suzy_at_Incheon_Airport_on_July_18,_2017_(4).jpg',
       'SHAQ Photo', 'CC BY 4.0', 'https://creativecommons.org/licenses/by/4.0'
WHERE NOT EXISTS (SELECT 1 FROM artist_portrait WHERE normalized_name = 'bae suzy');

INSERT INTO artist_portrait
    (normalized_name, display_name, search_names, image_url, source_url, attribution, license, license_url)
SELECT '웬디', '웬디', '웬디 / Wendy / Red Velvet',
       'https://thumb.wikimedia.org/wikipedia/commons/thumb/2/27/Red_Velvet_Wendy_2025-03-20.png/500px-Red_Velvet_Wendy_2025-03-20.png',
       'https://commons.wikimedia.org/wiki/File:Red_Velvet_Wendy_2025-03-20.png',
       'K-POPIT 케이팝잇 / TV10', 'CC BY 3.0', 'https://creativecommons.org/licenses/by/3.0'
WHERE NOT EXISTS (SELECT 1 FROM artist_portrait WHERE normalized_name = '웬디');

INSERT INTO artist_portrait
    (normalized_name, display_name, search_names, image_url, source_url, attribution, license, license_url)
SELECT 'wendy', '웬디', '웬디 / Wendy / Red Velvet',
       'https://thumb.wikimedia.org/wikipedia/commons/thumb/2/27/Red_Velvet_Wendy_2025-03-20.png/500px-Red_Velvet_Wendy_2025-03-20.png',
       'https://commons.wikimedia.org/wiki/File:Red_Velvet_Wendy_2025-03-20.png',
       'K-POPIT 케이팝잇 / TV10', 'CC BY 3.0', 'https://creativecommons.org/licenses/by/3.0'
WHERE NOT EXISTS (SELECT 1 FROM artist_portrait WHERE normalized_name = 'wendy');

INSERT INTO artist_portrait
    (normalized_name, display_name, search_names, image_url, source_url, attribution, license, license_url)
SELECT '이은미', '이은미', '이은미 / Lee Eun-mi',
       'https://commons.wikimedia.org/wiki/Special:Redirect/file/Lee_Eun-Mi.jpg',
       'https://commons.wikimedia.org/wiki/File:Lee_Eun-Mi.jpg',
       'EXPO 2012 YEOSU KOREA', 'CC BY 2.0 kr', 'https://creativecommons.org/licenses/by/2.0/kr/deed.en'
WHERE NOT EXISTS (SELECT 1 FROM artist_portrait WHERE normalized_name = '이은미');

INSERT INTO artist_portrait
    (normalized_name, display_name, search_names, image_url, source_url, attribution, license, license_url)
SELECT 'lee eun-mi', '이은미', '이은미 / Lee Eun-mi',
       'https://commons.wikimedia.org/wiki/Special:Redirect/file/Lee_Eun-Mi.jpg',
       'https://commons.wikimedia.org/wiki/File:Lee_Eun-Mi.jpg',
       'EXPO 2012 YEOSU KOREA', 'CC BY 2.0 kr', 'https://creativecommons.org/licenses/by/2.0/kr/deed.en'
WHERE NOT EXISTS (SELECT 1 FROM artist_portrait WHERE normalized_name = 'lee eun-mi');

INSERT INTO artist_portrait
    (normalized_name, display_name, search_names, image_url, source_url, attribution, license, license_url)
SELECT '카더가든', '카더가든', '카더가든 / Car, the Garden',
       'https://commons.wikimedia.org/wiki/Special:Redirect/file/Car,_the_Garden_in_2026.png',
       'https://commons.wikimedia.org/wiki/File:Car,_the_Garden_in_2026.png',
       'K-POPIT 케이팝잇', 'CC BY 4.0', 'https://creativecommons.org/licenses/by/4.0'
WHERE NOT EXISTS (SELECT 1 FROM artist_portrait WHERE normalized_name = '카더가든');

INSERT INTO artist_portrait
    (normalized_name, display_name, search_names, image_url, source_url, attribution, license, license_url)
SELECT 'car, the garden', '카더가든', '카더가든 / Car, the Garden',
       'https://commons.wikimedia.org/wiki/Special:Redirect/file/Car,_the_Garden_in_2026.png',
       'https://commons.wikimedia.org/wiki/File:Car,_the_Garden_in_2026.png',
       'K-POPIT 케이팝잇', 'CC BY 4.0', 'https://creativecommons.org/licenses/by/4.0'
WHERE NOT EXISTS (SELECT 1 FROM artist_portrait WHERE normalized_name = 'car, the garden');

INSERT INTO artist_portrait
    (normalized_name, display_name, search_names, image_url, source_url, attribution, license, license_url)
SELECT '규현', '규현', '규현 / Kyuhyun / 조규현',
       'https://thumb.wikimedia.org/wikipedia/commons/thumb/e/e0/Kyuhyun_in_Bangkok.jpg/330px-Kyuhyun_in_Bangkok.jpg',
       'https://commons.wikimedia.org/wiki/File:Kyuhyun_in_Bangkok.jpg',
       'Sry85', 'CC BY-SA 3.0', 'https://creativecommons.org/licenses/by-sa/3.0'
WHERE NOT EXISTS (SELECT 1 FROM artist_portrait WHERE normalized_name = '규현');

INSERT INTO artist_portrait
    (normalized_name, display_name, search_names, image_url, source_url, attribution, license, license_url)
SELECT 'kyuhyun', '규현', '규현 / Kyuhyun / 조규현',
       'https://thumb.wikimedia.org/wikipedia/commons/thumb/e/e0/Kyuhyun_in_Bangkok.jpg/330px-Kyuhyun_in_Bangkok.jpg',
       'https://commons.wikimedia.org/wiki/File:Kyuhyun_in_Bangkok.jpg',
       'Sry85', 'CC BY-SA 3.0', 'https://creativecommons.org/licenses/by-sa/3.0'
WHERE NOT EXISTS (SELECT 1 FROM artist_portrait WHERE normalized_name = 'kyuhyun');

INSERT INTO artist_portrait
    (normalized_name, display_name, search_names, image_url, source_url, attribution, license, license_url)
SELECT '박완규', '박완규', '박완규 / Park Wan-kyu',
       'https://commons.wikimedia.org/wiki/Special:Redirect/file/Park_Wan-Kyu.jpg',
       'https://commons.wikimedia.org/wiki/File:Park_Wan-Kyu.jpg',
       '여니수니', 'CC BY 2.0 kr', 'https://creativecommons.org/licenses/by/2.0/kr/deed.en'
WHERE NOT EXISTS (SELECT 1 FROM artist_portrait WHERE normalized_name = '박완규');

INSERT INTO artist_portrait
    (normalized_name, display_name, search_names, image_url, source_url, attribution, license, license_url)
SELECT 'park wan-kyu', '박완규', '박완규 / Park Wan-kyu',
       'https://commons.wikimedia.org/wiki/Special:Redirect/file/Park_Wan-Kyu.jpg',
       'https://commons.wikimedia.org/wiki/File:Park_Wan-Kyu.jpg',
       '여니수니', 'CC BY 2.0 kr', 'https://creativecommons.org/licenses/by/2.0/kr/deed.en'
WHERE NOT EXISTS (SELECT 1 FROM artist_portrait WHERE normalized_name = 'park wan-kyu');

INSERT INTO artist_portrait
    (normalized_name, display_name, search_names, image_url, source_url, attribution, license, license_url)
SELECT '크러쉬', '크러쉬', '크러쉬 / Crush / 신효섭',
       'https://thumb.wikimedia.org/wikipedia/commons/thumb/d/df/Crush_Korean_singer.jpg/500px-Crush_Korean_singer.jpg',
       'https://commons.wikimedia.org/wiki/File:Crush_Korean_singer.jpg',
       'Pabian', 'CC BY-SA 4.0', 'https://creativecommons.org/licenses/by-sa/4.0'
WHERE NOT EXISTS (SELECT 1 FROM artist_portrait WHERE normalized_name = '크러쉬');

INSERT INTO artist_portrait
    (normalized_name, display_name, search_names, image_url, source_url, attribution, license, license_url)
SELECT 'crush', '크러쉬', '크러쉬 / Crush / 신효섭',
       'https://thumb.wikimedia.org/wikipedia/commons/thumb/d/df/Crush_Korean_singer.jpg/500px-Crush_Korean_singer.jpg',
       'https://commons.wikimedia.org/wiki/File:Crush_Korean_singer.jpg',
       'Pabian', 'CC BY-SA 4.0', 'https://creativecommons.org/licenses/by-sa/4.0'
WHERE NOT EXISTS (SELECT 1 FROM artist_portrait WHERE normalized_name = 'crush');

INSERT INTO artist_portrait
    (normalized_name, display_name, search_names, image_url, source_url, attribution, license, license_url)
SELECT 'bmk', 'BMK', 'BMK / Big Mama King',
       'https://thumb.wikimedia.org/wikipedia/commons/thumb/1/10/Big_Mama_King_%28BMK%29.jpg/500px-Big_Mama_King_%28BMK%29.jpg',
       'https://commons.wikimedia.org/wiki/File:Big_Mama_King_(BMK).jpg',
       'LG Electronics', 'CC BY 2.0', 'https://creativecommons.org/licenses/by/2.0'
WHERE NOT EXISTS (SELECT 1 FROM artist_portrait WHERE normalized_name = 'bmk');

INSERT INTO artist_portrait
    (normalized_name, display_name, search_names, image_url, source_url, attribution, license, license_url)
SELECT '대성', '대성', '대성 / Daesung / D-Lite',
       'https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a3/Daesung_2012.jpg/500px-Daesung_2012.jpg',
       'https://commons.wikimedia.org/wiki/File:Daesung_2012.jpg',
       'Nicole Voon', 'CC BY 2.0', 'https://creativecommons.org/licenses/by/2.0'
WHERE NOT EXISTS (SELECT 1 FROM artist_portrait WHERE normalized_name = '대성');

INSERT INTO artist_portrait
    (normalized_name, display_name, search_names, image_url, source_url, attribution, license, license_url)
SELECT 'daesung', '대성', '대성 / Daesung / D-Lite',
       'https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a3/Daesung_2012.jpg/500px-Daesung_2012.jpg',
       'https://commons.wikimedia.org/wiki/File:Daesung_2012.jpg',
       'Nicole Voon', 'CC BY 2.0', 'https://creativecommons.org/licenses/by/2.0'
WHERE NOT EXISTS (SELECT 1 FROM artist_portrait WHERE normalized_name = 'daesung');

INSERT INTO artist_portrait
    (normalized_name, display_name, search_names, image_url, source_url, attribution, license, license_url)
SELECT '동방신기', '동방신기', '동방신기 / TVXQ / DBSK / Tohoshinki',
       'https://commons.wikimedia.org/wiki/Special:Redirect/file/180320_%EB%8F%99%EB%B0%A9%EC%8B%A0%EA%B8%B0_02.jpg',
       'https://commons.wikimedia.org/wiki/File:180320_%EB%8F%99%EB%B0%A9%EC%8B%A0%EA%B8%B0_02.jpg',
       'dispatchsns', 'CC BY 3.0', 'https://creativecommons.org/licenses/by/3.0'
WHERE NOT EXISTS (SELECT 1 FROM artist_portrait WHERE normalized_name = '동방신기');

INSERT INTO artist_portrait
    (normalized_name, display_name, search_names, image_url, source_url, attribution, license, license_url)
SELECT 'tvxq', '동방신기', '동방신기 / TVXQ / DBSK / Tohoshinki',
       'https://commons.wikimedia.org/wiki/Special:Redirect/file/180320_%EB%8F%99%EB%B0%A9%EC%8B%A0%EA%B8%B0_02.jpg',
       'https://commons.wikimedia.org/wiki/File:180320_%EB%8F%99%EB%B0%A9%EC%8B%A0%EA%B8%B0_02.jpg',
       'dispatchsns', 'CC BY 3.0', 'https://creativecommons.org/licenses/by/3.0'
WHERE NOT EXISTS (SELECT 1 FROM artist_portrait WHERE normalized_name = 'tvxq');

INSERT INTO artist_portrait
    (normalized_name, display_name, search_names, image_url, source_url, attribution, license, license_url)
SELECT 'oasis', 'Oasis', 'Oasis / 오아시스',
       'https://thumb.wikimedia.org/wikipedia/commons/thumb/b/bc/Oasis_2005.jpg/960px-Oasis_2005.jpg',
       'https://commons.wikimedia.org/wiki/File:Oasis_2005.jpg',
       'Etsai from Japan', 'CC BY-SA 2.0', 'https://creativecommons.org/licenses/by-sa/2.0'
WHERE NOT EXISTS (SELECT 1 FROM artist_portrait WHERE normalized_name = 'oasis');

INSERT INTO artist_portrait
    (normalized_name, display_name, search_names, image_url, source_url, attribution, license, license_url)
SELECT '크리스토퍼', 'Christopher', 'Christopher / 크리스토퍼',
       'https://thumb.wikimedia.org/wikipedia/commons/thumb/f/fb/Christopher_%28sanger%29.jpg/960px-Christopher_%28sanger%29.jpg',
       'https://commons.wikimedia.org/wiki/File:Christopher_(sanger).jpg',
       'Kim Matthäi Leland', 'CC BY-SA 3.0', 'https://creativecommons.org/licenses/by-sa/3.0'
WHERE NOT EXISTS (SELECT 1 FROM artist_portrait WHERE normalized_name = '크리스토퍼');

INSERT INTO artist_portrait
    (normalized_name, display_name, search_names, image_url, source_url, attribution, license, license_url)
SELECT 'christopher', 'Christopher', 'Christopher / 크리스토퍼',
       'https://thumb.wikimedia.org/wikipedia/commons/thumb/f/fb/Christopher_%28sanger%29.jpg/960px-Christopher_%28sanger%29.jpg',
       'https://commons.wikimedia.org/wiki/File:Christopher_(sanger).jpg',
       'Kim Matthäi Leland', 'CC BY-SA 3.0', 'https://creativecommons.org/licenses/by-sa/3.0'
WHERE NOT EXISTS (SELECT 1 FROM artist_portrait WHERE normalized_name = 'christopher');

INSERT INTO artist_portrait
    (normalized_name, display_name, search_names, image_url, source_url, attribution, license, license_url)
SELECT '이정현', '이정현', '이정현 / Lee Jung-hyun',
       'https://commons.wikimedia.org/wiki/Special:Redirect/file/%27%EC%A0%9C2%ED%9A%8C_%EB%8D%94_%EC%84%9C%EC%9A%B8_%EC%96%B4%EC%9B%8C%EC%A6%88%27_%EC%9D%B4%EC%A0%95%ED%98%84.jpg',
       'https://commons.wikimedia.org/wiki/File:%27%EC%A0%9C2%ED%9A%8C_%EB%8D%94_%EC%84%9C%EC%9A%B8_%EC%96%B4%EC%9B%8C%EC%A6%88%27_%EC%9D%B4%EC%A0%95%ED%98%84.jpg',
       'Korea Dispatch', 'CC BY 3.0', 'https://creativecommons.org/licenses/by/3.0'
WHERE NOT EXISTS (SELECT 1 FROM artist_portrait WHERE normalized_name = '이정현');

INSERT INTO artist_portrait
    (normalized_name, display_name, search_names, image_url, source_url, attribution, license, license_url)
SELECT 'lee jung-hyun', '이정현', '이정현 / Lee Jung-hyun',
       'https://commons.wikimedia.org/wiki/Special:Redirect/file/%27%EC%A0%9C2%ED%9A%8C_%EB%8D%94_%EC%84%9C%EC%9A%B8_%EC%96%B4%EC%9B%8C%EC%A6%88%27_%EC%9D%B4%EC%A0%95%ED%98%84.jpg',
       'https://commons.wikimedia.org/wiki/File:%27%EC%A0%9C2%ED%9A%8C_%EB%8D%94_%EC%84%9C%EC%9A%B8_%EC%96%B4%EC%9B%8C%EC%A6%88%27_%EC%9D%B4%EC%A0%95%ED%98%84.jpg',
       'Korea Dispatch', 'CC BY 3.0', 'https://creativecommons.org/licenses/by/3.0'
WHERE NOT EXISTS (SELECT 1 FROM artist_portrait WHERE normalized_name = 'lee jung-hyun');
