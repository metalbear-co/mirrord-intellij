Fixed the SBT run configuration breaking on IntelliJ IDEA 2026.2 and later, where
the Scala plugin made `SbtCommandLineState` final and removed
`SbtRunConfiguration.preprocessTasks`.
