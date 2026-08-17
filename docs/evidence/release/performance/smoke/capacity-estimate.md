# Release capacity estimate from measured relation sizes

- Source profile: `smoke`
- Measured table heap + index bytes: `59711488` (`56.95 MiB`)
- Projected default release relation bytes: `5971148800` (`5.561 GiB`)
- Transient relation/index/WAL allowance: `3x` (`16.683 GiB`)
- Additional host reserve: `4 GiB`
- Recommended free space before full run: `21 GiB`

The projection scales each measured table heap and aggregate table-index size by that table family’s row-count ratio from the recorded source profile to the default release profile. When the source is already the release profile, the projection is the measured full-scale relation footprint. PostgreSQL page fill, B-tree height and WAL are nonlinear; the 3x allowance and 4 GiB reserve deliberately keep the runner from treating a linear estimate as exact.
