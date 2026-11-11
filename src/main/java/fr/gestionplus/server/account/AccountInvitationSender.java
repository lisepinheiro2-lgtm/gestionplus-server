package fr.gestionplus.server.account;

import java.util.UUID;

public interface AccountInvitationSender {

	boolean send(String email, UUID invitationID, String token);
}
