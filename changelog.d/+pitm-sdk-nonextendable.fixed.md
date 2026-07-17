Build the Windows pitm fake JDK with the platform's own `ProjectJdkImpl` instead of implementing the non-extendable `Sdk` interface, which JetBrains' Plugin Verifier rejects.
