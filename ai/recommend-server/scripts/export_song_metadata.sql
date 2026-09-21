-- MICON 운영 DB에서 카탈로그 곡의 장르와 MAIN 가수 ID를 뽑는다. 읽기 전용이다.
-- 한 줄에 JSON 한 개가 나오므로 그대로 파일로 받아 apply_db_metadata.py 에 넘기면 된다.
--
-- 실행 예 (BE 호스트에서):
--   docker exec -i <mysql컨테이너> sh -lc 'MYSQL_PWD=$(cat /run/secrets/mysql_app_password) \
--     mysql --protocol=tcp -h 127.0.0.1 -u micon_app -D micon --batch --raw --skip-column-names' \
--     < export_song_metadata.sql > song-metadata-export.jsonl

START TRANSACTION READ ONLY;

SELECT JSON_OBJECT(
    'trackId',    m.catalog_track_id,
    'dbSongId',   s.song_id,
    'title',      s.title,
    'status',     s.status,
    'artistId',   (SELECT ss.singer_id
                     FROM song_singer ss
                    WHERE ss.song_id = s.song_id AND ss.role = 'MAIN'
                    ORDER BY ss.display_order, ss.singer_id LIMIT 1),
    'artistName', (SELECT sg.name
                     FROM song_singer ss JOIN singer sg ON sg.singer_id = ss.singer_id
                    WHERE ss.song_id = s.song_id AND ss.role = 'MAIN'
                    ORDER BY ss.display_order, ss.singer_id LIMIT 1),
    'mainSingerCount', (SELECT COUNT(*)
                          FROM song_singer ss
                         WHERE ss.song_id = s.song_id AND ss.role = 'MAIN'),
    'genres',     COALESCE((SELECT JSON_ARRAYAGG(JSON_OBJECT('name', g.name, 'isPrimary', sgx.is_primary))
                              FROM song_genre sgx JOIN genre g ON g.genre_id = sgx.genre_id
                             WHERE sgx.song_id = s.song_id), JSON_ARRAY())
)
  FROM song s
  JOIN song_metadata m ON m.song_id = s.song_id
 WHERE m.catalog_track_id IS NOT NULL
   AND s.status = 'ACTIVE';

COMMIT;
