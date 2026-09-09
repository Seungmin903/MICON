-- MICON genre seed data
-- Target: MySQL 8.0.16+ / MICON_COMPLETE_DDL.sql
-- Safe to run repeatedly because normalized_name is unique.

SET NAMES utf8mb4;
USE `micon`;

INSERT INTO `genre` (`name`, `normalized_name`, `category`, `source_url`) VALUES
    ('발라드', 'kpop-ballad', 'KOREAN', 'https://music.bugs.co.kr/genre/chart/kpop/ballad/total/day'),
    ('댄스/팝', 'kpop-dance-pop', 'KOREAN', 'https://music.bugs.co.kr/genre/chart/kpop/dance/total/day'),
    ('포크/어쿠스틱', 'kpop-folk-acoustic', 'KOREAN', 'https://music.bugs.co.kr/genre/chart/kpop/folk/total/day'),
    ('아이돌', 'kpop-idol', 'KOREAN', 'https://music.bugs.co.kr/genre/chart/kpop/idol/total/day'),
    ('랩/힙합', 'kpop-rap-hiphop', 'KOREAN', 'https://music.bugs.co.kr/genre/chart/kpop/rnh/total/day'),
    ('알앤비/소울', 'kpop-rnb-soul', 'KOREAN', 'https://music.bugs.co.kr/genre/chart/kpop/rns/total/day'),
    ('일렉트로닉', 'kpop-electronic', 'KOREAN', 'https://music.bugs.co.kr/genre/chart/kpop/elec/total/day'),
    ('락/메탈', 'kpop-rock-metal', 'KOREAN', 'https://music.bugs.co.kr/genre/chart/kpop/rock/total/day'),
    ('재즈', 'kpop-jazz', 'KOREAN', 'https://music.bugs.co.kr/genre/chart/kpop/jazz/total/day'),
    ('인디', 'kpop-indie', 'KOREAN', 'https://music.bugs.co.kr/genre/chart/kpop/indie/total/day'),
    ('성인가요', 'kpop-adult', 'KOREAN', 'https://music.bugs.co.kr/genre/chart/kpop/adultkpop/total/day'),

    ('팝', 'global-pop', 'INTERNATIONAL', 'https://music.bugs.co.kr/genre/chart/pop/pop/total/day'),
    ('랩/힙합', 'global-rap-hiphop', 'INTERNATIONAL', 'https://music.bugs.co.kr/genre/chart/pop/hiphop/total/day'),
    ('알앤비/소울', 'global-rnb-soul', 'INTERNATIONAL', 'https://music.bugs.co.kr/genre/chart/pop/rnb/total/day'),
    ('일렉트로닉', 'global-electronic', 'INTERNATIONAL', 'https://music.bugs.co.kr/genre/chart/pop/elec/total/day'),
    ('락', 'global-rock', 'INTERNATIONAL', 'https://music.bugs.co.kr/genre/chart/pop/rock/total/day'),
    ('메탈', 'global-metal', 'INTERNATIONAL', 'https://music.bugs.co.kr/genre/chart/pop/metal/total/day'),
    ('재즈', 'global-jazz', 'INTERNATIONAL', 'https://music.bugs.co.kr/genre/chart/pop/jazz/total/day'),

    ('OST', 'etc-ost', 'ETC', 'https://music.bugs.co.kr/genre/chart/etc/nost/total/day'),
    ('클래식', 'etc-classical', 'ETC', 'https://music.bugs.co.kr/genre/etc/nclassic/home'),
    ('뉴에이지', 'etc-new-age', 'ETC', 'https://music.bugs.co.kr/genre/chart/etc/nnewage/total/day'),
    ('J-POP', 'etc-jpop', 'ETC', 'https://music.bugs.co.kr/genre/chart/etc/njpop/total/day'),
    ('월드뮤직', 'etc-world-music', 'ETC', 'https://music.bugs.co.kr/genre/chart/etc/nwpop/total/day'),
    ('국악', 'etc-korean-traditional', 'ETC', 'https://music.bugs.co.kr/genre/chart/etc/nkclassic/total/day'),
    ('CCM/종교', 'etc-ccm-religion', 'ETC', 'https://music.bugs.co.kr/genre/chart/etc/nccm/total/day'),
    ('어린이', 'etc-children', 'ETC', 'https://music.bugs.co.kr/genre/chart/etc/nchildren/total/day'),
    ('태교', 'etc-prenatal', 'ETC', 'https://music.bugs.co.kr/genre/chart/etc/nprenatal/total/day'),
    ('캐롤', 'etc-carol', 'ETC', 'https://music.bugs.co.kr/genre/chart/etc/ncarol/total/day')
ON DUPLICATE KEY UPDATE
    `name` = VALUES(`name`),
    `category` = VALUES(`category`),
    `source_url` = VALUES(`source_url`),
    `updated_at` = CURRENT_TIMESTAMP(6);
