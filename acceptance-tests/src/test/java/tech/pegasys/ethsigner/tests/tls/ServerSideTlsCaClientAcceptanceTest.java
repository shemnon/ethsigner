/*
 * Copyright 2020 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.ethsigner.tests.tls;

import static java.nio.file.Files.writeString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.ThrowableAssert.catchThrowable;

import tech.pegasys.ethsigner.core.config.TlsOptions;
import tech.pegasys.ethsigner.tests.dsl.http.HttpRequest;
import tech.pegasys.ethsigner.tests.dsl.node.NodeConfigurationBuilder;
import tech.pegasys.ethsigner.tests.dsl.node.NodePorts;
import tech.pegasys.ethsigner.tests.dsl.signer.Signer;
import tech.pegasys.ethsigner.tests.dsl.signer.SignerConfigurationBuilder;
import tech.pegasys.ethsigner.tests.dsl.tls.BasicTlsOptions;
import tech.pegasys.ethsigner.tests.dsl.tls.ClientTlsConfig;
import tech.pegasys.ethsigner.tests.dsl.tls.OkHttpClientHelpers;
import tech.pegasys.ethsigner.tests.dsl.tls.TlsCertificateDefinition;
import tech.pegasys.ethsigner.tests.tls.support.BasicClientAuthConstraints;

import java.nio.file.Path;
import java.util.Optional;
import javax.net.ssl.SSLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ServerSideTlsCaClientAcceptanceTest {

  private static final TlsCertificateDefinition serverCert =
      TlsCertificateDefinition.loadFromResource("tls/cert1.pfx", "password");
  private static final TlsCertificateDefinition clientCert =
      TlsCertificateDefinition.loadFromResource("tls/cert2.pfx", "password2");

  private Signer createEthSigner(final TlsCertificateDefinition certInCa, final Path testDir)
      throws Exception {

    final Path passwordPath = testDir.resolve("keystore.passwd");
    writeString(passwordPath, serverCert.getPassword());

    final TlsOptions serverOptions =
        new BasicTlsOptions(
            serverCert.getPkcs12File(),
            passwordPath.toFile(),
            Optional.of(BasicClientAuthConstraints.caOnly()));

    final SignerConfigurationBuilder configBuilder = new SignerConfigurationBuilder();
    configBuilder.withServerTlsOptions(serverOptions);
    configBuilder.withOverriddenCA(certInCa);

    final ClientTlsConfig clientTlsConfig = new ClientTlsConfig(serverCert, clientCert);

    return new Signer(
        configBuilder.build(),
        new NodeConfigurationBuilder().build(),
        new NodePorts(1, 2),
        clientTlsConfig);
  }

  @Test
  void clientWithCertificateNotInCertificateAuthorityCanConnectAndQueryAccounts(
      @TempDir final Path tempDir) throws Exception {
    final Signer ethSigner = createEthSigner(clientCert, tempDir);
    ethSigner.start();
    ethSigner.awaitStartupCompletion();

    assertThat(ethSigner.accounts().list()).isNotEmpty();
  }

  @Test
  void clientNotInCaFailedToConnectToEthSigner(@TempDir final Path tempDir) throws Exception {
    final Signer ethSigner = createEthSigner(clientCert, tempDir);
    ethSigner.start();
    ethSigner.awaitStartupCompletion();

    // Create a client which presents the server cert (not in CA) - it should fail to connect.
    final ClientTlsConfig clientTlsConfig = new ClientTlsConfig(serverCert, serverCert);
    final HttpRequest rawRequests =
        new HttpRequest(
            ethSigner.getUrl(),
            OkHttpClientHelpers.createOkHttpClient(Optional.of(clientTlsConfig)));

    final Throwable thrown = catchThrowable(() -> rawRequests.get("/upcheck"));

    assertThat(thrown.getCause()).isInstanceOf(SSLException.class);
  }
}
