package dev.sultanov.keycloak.multitenancy.resource;

import dev.sultanov.keycloak.multitenancy.model.TenantInvitationModel;
import dev.sultanov.keycloak.multitenancy.model.TenantModel;
import dev.sultanov.keycloak.multitenancy.resource.representation.TenantInvitationRepresentation;
import dev.sultanov.keycloak.multitenancy.util.EmailUtil;
import java.net.URI;
import java.util.Optional;
import java.util.stream.Stream;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.ClientErrorException;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.keycloak.events.admin.OperationType;
import org.keycloak.models.Constants;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;

public class TenantInvitationsResource extends AbstractAdminResource<TenantAdminAuth> {

    private final TenantModel tenant;

    public TenantInvitationsResource(RealmModel realm, TenantModel tenant) {
        super(realm);
        this.tenant = tenant;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createInvitation(TenantInvitationRepresentation request) {
        String email = request.getEmail();
        if (!isValidEmail(email)) {
            throw new BadRequestException("Invalid email: " + email);
        }
        email = email.toLowerCase();

        if (tenant.getInvitationsByEmail(email).findAny().isPresent()) {
            throw new ClientErrorException(String.format("Invitation for %s already exists.", email), Response.Status.CONFLICT);
        }

        UserModel user = KeycloakModelUtils.findUserByNameOrEmail(session, realm, email);
        if (user != null && tenant.hasMembership(user)) {
            throw new ClientErrorException(String.format("%s is already a member of this organization.", email), Response.Status.CONFLICT);
        }

        try {
            TenantInvitationModel invitation = tenant.addInvitation(email, auth.getUser(), request.getRoles());
            TenantInvitationRepresentation representation = ModelMapper.toRepresentation(invitation);

            EmailUtil.sendInvitationEmail(session, email, tenant.getName());

            adminEvent.operation(OperationType.CREATE)
                    .resourcePath(session.getContext().getUri(), representation.getId())
                    .representation(representation)
                    .success();

            URI location = session.getContext().getUri().getAbsolutePathBuilder().path(representation.getId()).build();
            return Response.created(location).build();
        } catch (Exception e) {
            throw new InternalServerErrorException(e);
        }
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Stream<TenantInvitationRepresentation> listInvitations(
            @QueryParam("search") String searchQuery,
            @QueryParam("first") Integer firstResult,
            @QueryParam("max") Integer maxResults) {
        Optional<String> search = Optional.ofNullable(searchQuery);
        firstResult = firstResult != null ? firstResult : 0;
        maxResults = maxResults != null ? maxResults : Constants.DEFAULT_MAX_RESULTS;

        return tenant.getInvitationsStream()
                .filter(i -> search.isEmpty() || i.getEmail().contains(search.get()))
                .skip(firstResult)
                .limit(maxResults)
                .map(ModelMapper::toRepresentation);
    }

    @DELETE
    @Path("{invitationId}")
    public Response removeInvitation(@PathParam("invitationId") String invitationId) {
        var revoked = tenant.revokeInvitation(invitationId);
        if (revoked) {
            adminEvent.operation(OperationType.DELETE)
                    .resourcePath(session.getContext().getUri())
                    .success();
            return Response.status(204).build();
        } else {
            throw new NotFoundException(String.format("No invitation with id %s", invitationId));
        }
    }

    private static boolean isValidEmail(String email) {
        if (email != null) {
            try {
                if (email.startsWith("mailto:")) {
                    email = email.substring(7);
                }
                InternetAddress emailAddr = new InternetAddress(email);
                emailAddr.validate();
                return true;
            } catch (AddressException e) {
                // ignore
            }
        }
        return false;
    }
}
