# Release capacity estimate from measured relation sizes

- Source profile: `smoke`
- Measured table heap + index bytes: `44195840` (`42.15 MiB`)
- Projected default release relation bytes: `4419584000` (`4.116 GiB`)
- Transient relation/index/WAL allowance: `3x` (`12.348 GiB`)
- Additional host reserve: `4 GiB`
- Recommended free space before full run: `17 GiB`

The projection scales each measured table heap and aggregate table-index size by that table family’s row-count ratio from the recorded source profile to the default release profile. When the source is already the release profile, the projection is the measured full-scale relation footprint. PostgreSQL page fill, B-tree height and WAL are nonlinear; the 3x allowance and 4 GiB reserve deliberately keep the runner from treating a linear estimate as exact.
