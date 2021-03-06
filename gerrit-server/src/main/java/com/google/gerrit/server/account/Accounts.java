// Copyright (C) 2017 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.account;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.ImmutableSetMultimap.toImmutableSetMultimap;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.account.externalids.ExternalIds;
import com.google.gerrit.server.config.AllUsersName;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.mail.send.OutgoingEmailValidator;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Class to access accounts. */
@Singleton
public class Accounts {
  private static final Logger log = LoggerFactory.getLogger(Accounts.class);

  private final boolean readFromGit;
  private final GitRepositoryManager repoManager;
  private final AllUsersName allUsersName;
  private final OutgoingEmailValidator emailValidator;
  private final ExternalIds externalIds;

  @Inject
  Accounts(
      @GerritServerConfig Config cfg,
      GitRepositoryManager repoManager,
      AllUsersName allUsersName,
      OutgoingEmailValidator emailValidator,
      ExternalIds externalIds) {
    this.readFromGit = cfg.getBoolean("user", null, "readAccountsFromGit", false);
    this.repoManager = repoManager;
    this.allUsersName = allUsersName;
    this.emailValidator = emailValidator;
    this.externalIds = externalIds;
  }

  public Account get(ReviewDb db, Account.Id accountId)
      throws OrmException, IOException, ConfigInvalidException {
    if (readFromGit) {
      try (Repository repo = repoManager.openRepository(allUsersName)) {
        return read(repo, accountId);
      }
    }

    return db.accounts().get(accountId);
  }

  public List<Account> get(ReviewDb db, Collection<Account.Id> accountIds)
      throws OrmException, IOException, ConfigInvalidException {
    if (readFromGit) {
      List<Account> accounts = new ArrayList<>(accountIds.size());
      try (Repository repo = repoManager.openRepository(allUsersName)) {
        for (Account.Id accountId : accountIds) {
          accounts.add(read(repo, accountId));
        }
      }
      return accounts;
    }

    return db.accounts().get(accountIds).toList();
  }

  /**
   * Returns the accounts with the given email.
   *
   * <p>Each email should belong to a single account only. This means if more than one account is
   * returned there is an inconsistency in the external IDs.
   *
   * <p>The accounts are retrieved via the external ID cache. Each access to the external ID cache
   * requires reading the SHA1 of the refs/meta/external-ids branch. If accounts for multiple emails
   * are needed it is more efficient to use {@link #byEmails(String...)} as this method reads the
   * SHA1 of the refs/meta/external-ids branch only once (and not once per email).
   *
   * @see #byEmails(String...)
   */
  public ImmutableSet<Account.Id> byEmail(String email) throws IOException {
    return externalIds.byEmail(email).stream().map(e -> e.accountId()).collect(toImmutableSet());
  }

  /**
   * Returns the accounts for the given emails.
   *
   * <p>Each email should belong to a single account only. This means if more than one account for
   * an email is returned there is an inconsistency in the external IDs.
   *
   * <p>The accounts are retrieved via the external ID cache. Each access to the external ID cache
   * requires reading the SHA1 of the refs/meta/external-ids branch. If accounts for multiple emails
   * are needed it is more efficient to use this method instead of {@link #byEmail(String)} as this
   * method reads the SHA1 of the refs/meta/external-ids branch only once (and not once per email).
   *
   * @see #byEmail(String)
   */
  public ImmutableSetMultimap<String, Account.Id> byEmails(String... emails) throws IOException {
    return externalIds
        .byEmails(emails)
        .entries()
        .stream()
        .collect(toImmutableSetMultimap(Map.Entry::getKey, e -> e.getValue().accountId()));
  }

  /**
   * Returns all accounts.
   *
   * @return all accounts
   */
  public List<Account> all(ReviewDb db) throws OrmException, IOException {
    if (readFromGit) {
      Set<Account.Id> accountIds = allIds();
      List<Account> accounts = new ArrayList<>(accountIds.size());
      try (Repository repo = repoManager.openRepository(allUsersName)) {
        for (Account.Id accountId : accountIds) {
          try {
            accounts.add(read(repo, accountId));
          } catch (Exception e) {
            log.error(String.format("Ignoring invalid account %s", accountId.get()), e);
          }
        }
      }
      return accounts;
    }

    return db.accounts().all().toList();
  }

  /**
   * Returns all account IDs.
   *
   * @return all account IDs
   */
  public Set<Account.Id> allIds() throws IOException {
    return readUserRefs().collect(toSet());
  }

  /**
   * Returns the first n account IDs.
   *
   * @param n the number of account IDs that should be returned
   * @return first n account IDs
   */
  public List<Account.Id> firstNIds(int n) throws IOException {
    return readUserRefs().sorted(comparing(id -> id.get())).limit(n).collect(toList());
  }

  /**
   * Checks if any account exists.
   *
   * @return {@code true} if at least one account exists, otherwise {@code false}
   */
  public boolean hasAnyAccount() throws IOException {
    try (Repository repo = repoManager.openRepository(allUsersName)) {
      return hasAnyAccount(repo);
    }
  }

  public static boolean hasAnyAccount(Repository repo) throws IOException {
    return readUserRefs(repo).findAny().isPresent();
  }

  private Stream<Account.Id> readUserRefs() throws IOException {
    try (Repository repo = repoManager.openRepository(allUsersName)) {
      return readUserRefs(repo);
    }
  }

  private Account read(Repository allUsersRepository, Account.Id accountId)
      throws IOException, ConfigInvalidException {
    AccountConfig accountConfig = new AccountConfig(emailValidator, accountId);
    accountConfig.load(allUsersRepository);
    return accountConfig.getAccount();
  }

  private static Stream<Account.Id> readUserRefs(Repository repo) throws IOException {
    return repo.getRefDatabase()
        .getRefs(RefNames.REFS_USERS)
        .values()
        .stream()
        .map(r -> Account.Id.fromRef(r.getName()))
        .filter(Objects::nonNull);
  }
}
