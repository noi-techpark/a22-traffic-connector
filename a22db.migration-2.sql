-- SPDX-FileCopyrightText: 2024 NOI Techpark <digital@noi.bz.it>
--
-- SPDX-License-Identifier: CC0-1.0

alter table a22.a22_station add min_timestamp integer;
alter table a22.a22_station add max_timestamp integer;

with minmax as (
    select at2.stationcode code, min(timestamp) as _min, max(timestamp) as _max 
    from a22.a22_traffic at2 
    group by at2.stationcode
)
UPDATE a22.a22_station as2
SET 
    min_timestamp = mm._min,
    max_timestamp = mm._max
FROM minmax mm
WHERE as2.code = mm.code;