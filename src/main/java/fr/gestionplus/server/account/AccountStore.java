package fr.gestionplus.server.account;

import fr.gestionplus.server.auth.UserAccount;

public interface AccountStore {

    void createAccount(UserAccount account);
}