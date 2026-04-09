Adde mirrord's SBT run configuration wrapper. When `useSbtShell` is set to true, mirrord plugin
rewrites the SBT shell command with environment variabels injection command chained with the
original. When set to false, mirrord plugin injects environment variables directly to the
run configuration and clean up once the run finishes.
