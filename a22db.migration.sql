-- SPDX-FileCopyrightText: 2024 NOI Techpark <digital@noi.bz.it>
--
-- SPDX-License-Identifier: CC0-1.0

alter table a22.a22_traffic add country text;
alter table a22.a22_traffic add license_plate_initials text;

drop index if exists a22.a22_traffic_stationcode_ix;
drop index if exists a22.a22_recent_data_ix_ccnew;
CREATE INDEX a22_recent_data_ix 
ON a22.a22_traffic USING btree (stationcode, "timestamp" DESC) WHERE ("timestamp" > 1728005677);
drop index if exists a22.a22_recent2_data_ix;

