// Generated from docs/openapi.json by `npm run gen:api` (openapi-typescript). Do not edit.

export interface paths {
    "/auth/login": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["login"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/auth/logout": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["logout"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/auth/refresh": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["refresh"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/auth/register": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["register"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/events": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listEvents"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/events/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getEvent"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/events/{id}/holds": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["hold"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/events/{id}/layout": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The event's static seat layout
         * @description Venue outline, stage, sections and every seat with its position and tier. It doesn't change after the event is created, so load it once. Seats are sorted by seat id as PostgreSQL orders uuid values (byte by byte, the same as comparing the lowercase text form). A seat's position in this list is its bit index in GET /events/{id}/seatmap.
         */
        get: operations["eventLayout"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/events/{id}/seatmap": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Which seats are available now
         * @description One bit per seat, in the order of the layout's seat list. Poll it again after a rejected hold; realtime updates with a sequence number arrive in M5. Base64 (RFC 4648 section 4, with padding) of ceil(seatCount / 8) bytes. Bit i describes seats[i] of GET /events/{id}/layout: it is bit (7 - i % 8) of byte floor(i / 8), i.e. the most significant bit first. 1 = AVAILABLE, 0 = held or sold. Unused bits of the last byte are 0.
         */
        get: operations["seatMap"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/holds/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete: operations["cancel"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/me/tickets": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["myTickets"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/orders": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["placeOrder"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/orders/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getOrder"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/tickets/keys": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["ticketKeys"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        EventDetail: {
            /** Format: date-time */
            dropAt: string;
            /** Format: uuid */
            id: string;
            /** Format: int32 */
            perUserLimit: number;
            /** Format: date-time */
            serverTime: string;
            /** Format: date-time */
            startsAt: string;
            /** @enum {string} */
            status: "SCHEDULED" | "LIVE" | "PAUSED" | "SOLD_OUT" | "ENDED";
            tiers: components["schemas"]["PriceTier"][];
            title: string;
            venue: components["schemas"]["VenueRef"];
        };
        EventLayout: {
            /** Format: uuid */
            eventId: string;
            /** @description Seats are sorted by seat id as PostgreSQL orders uuid values (byte by byte, the same as comparing the lowercase text form). A seat's position in this list is its bit index in GET /events/{id}/seatmap. */
            seats: components["schemas"]["LayoutSeat"][];
            venue: components["schemas"]["VenueLayout"];
        };
        EventList: {
            items: components["schemas"]["EventSummary"][];
            /** Format: date-time */
            serverTime: string;
        };
        EventSummary: {
            /** Format: date-time */
            dropAt: string;
            /** Format: uuid */
            id: string;
            /** Format: int32 */
            perUserLimit: number;
            /** Format: date-time */
            startsAt: string;
            /** @enum {string} */
            status: "SCHEDULED" | "LIVE" | "PAUSED" | "SOLD_OUT" | "ENDED";
            title: string;
            venue: components["schemas"]["VenueRef"];
        };
        FieldError: {
            field: string;
            message: string;
        };
        HoldResponse: {
            /** Format: uuid */
            eventId: string;
            /** Format: date-time */
            expiresAt: string;
            /** Format: uuid */
            holdId: string;
            seatIds: string[];
            /** Format: date-time */
            serverTime: string;
        };
        HoldSeatsRequest: {
            seatIds: string[];
        };
        Jwk: {
            /** @example EdDSA */
            alg: string;
            /** @example Ed25519 */
            crv: string;
            /**
             * @description Key id: hex of the first 4 bytes of SHA-256 over the raw public key
             * @example 1a2b3c4d
             */
            kid: string;
            /** @example OKP */
            kty: string;
            /** @example sig */
            use: string;
            /** @description The raw 32-byte Ed25519 public key, base64url without padding */
            x: string;
        };
        KeySet: {
            keys: components["schemas"]["Jwk"][];
        };
        LayoutPoint: {
            /** Format: double */
            x: number;
            /** Format: double */
            y: number;
        };
        LayoutRect: {
            /** Format: double */
            height: number;
            /** Format: double */
            width: number;
            /** Format: double */
            x: number;
            /** Format: double */
            y: number;
        };
        LayoutSeat: {
            accessible: boolean;
            aisle: boolean;
            /** Format: uuid */
            id: string;
            /**
             * Format: int32
             * @example 1
             */
            number: number;
            /** @example A */
            row: string;
            /**
             * @description Section code
             * @example FL-L
             */
            section: string;
            /**
             * Format: uuid
             * @description The seat's price tier; prices are in GET /events/{id}
             */
            tierId: string;
            /**
             * Format: double
             * @description Seat centre
             */
            x: number;
            /** Format: double */
            y: number;
        };
        LayoutSection: {
            code: string;
            /** @description Where the section's name is drawn when zoomed out */
            label: components["schemas"]["LayoutPoint"];
            name: string;
            /** @description Polygon of [x, y] points, drawn as a block when zoomed out */
            outline: number[][];
        };
        LoginRequest: {
            email: string;
            password: string;
        };
        MyTicketsResponse: {
            items: components["schemas"]["TicketResponse"][];
        };
        OrderItemResponse: {
            /** Format: int64 */
            pricePaise: number;
            /** Format: uuid */
            seatId: string;
        };
        OrderResponse: {
            /** Format: date-time */
            createdAt: string;
            /** Format: uuid */
            eventId: string;
            /** Format: uuid */
            holdId: string;
            items: components["schemas"]["OrderItemResponse"][];
            /** Format: uuid */
            orderId: string;
            /** @enum {string} */
            status: "PENDING" | "PAID" | "CANCELLED" | "REFUNDED";
            /** Format: int64 */
            totalPaise: number;
        };
        PlaceOrderRequest: {
            /** Format: uuid */
            holdId: string;
        };
        PriceTier: {
            /** Format: uuid */
            id: string;
            name: string;
            /** Format: int64 */
            pricePaise: number;
        };
        /** @description RFC 7807 problem details, served as application/problem+json. */
        Problem: {
            /**
             * @description Stable error code (PRD section 10). Branch on this, never on title or detail.
             * @enum {string}
             */
            code: "VALIDATION_FAILED" | "UNAUTHENTICATED" | "INVALID_CREDENTIALS" | "INVALID_REFRESH_TOKEN" | "FORBIDDEN" | "EMAIL_TAKEN" | "EVENT_NOT_FOUND" | "NOT_FOUND" | "METHOD_NOT_ALLOWED" | "UNSUPPORTED_MEDIA_TYPE" | "SEAT_TAKEN" | "SEAT_CONTENDED" | "LIMIT_EXCEEDED" | "SALE_NOT_OPEN" | "HOLD_NOT_FOUND" | "HOLD_CONVERTED" | "HOLD_EXPIRED" | "PAYMENT_FAILED" | "ORDER_NOT_FOUND" | "REQUEST_FAILED" | "INTERNAL_ERROR";
            /** @description Human-readable; may change. */
            detail?: string;
            /** @description INTERNAL_ERROR only: the id the server logged the error under. */
            errorId?: string;
            /** @description VALIDATION_FAILED on a request body: one entry per invalid field. */
            errors?: components["schemas"]["FieldError"][];
            /** @description The request path. */
            instance?: string;
            /**
             * Format: int32
             * @description LIMIT_EXCEEDED only: the event's per-user ticket limit.
             */
            limit?: number;
            /** @description SEAT_TAKEN, SEAT_CONTENDED and LIMIT_EXCEEDED: the seats that caused the rejection. */
            seatIds?: string[];
            /** Format: int32 */
            status: number;
            /**
             * Format: int64
             * @description SEAT_TAKEN only: milliseconds since the most recently taken of seatIds was taken.
             */
            takenAgoMs?: number;
            title: string;
            /** @description Always about:blank for now. */
            type?: string;
        };
        RegisterRequest: {
            /** Format: email */
            email: string;
            /** @description At least 12 characters and at most 72 UTF-8 bytes */
            password: string;
        };
        SeatMap: {
            /**
             * Format: date-time
             * @description When this snapshot was read, by the server's clock
             */
            asOf: string;
            /** @description Base64 (RFC 4648 section 4, with padding) of ceil(seatCount / 8) bytes. Bit i describes seats[i] of GET /events/{id}/layout: it is bit (7 - i % 8) of byte floor(i / 8), i.e. the most significant bit first. 1 = AVAILABLE, 0 = held or sold. Unused bits of the last byte are 0. */
            available: string;
            /** Format: uuid */
            eventId: string;
            /**
             * Format: int32
             * @description Number of seats, equal to the length of the layout's seat list
             */
            seatCount: number;
        };
        TicketResponse: {
            /** Format: uuid */
            eventId: string;
            /** Format: date-time */
            issuedAt: string;
            /** Format: uuid */
            orderId: string;
            /** @description What the QR code encodes: base64url(payload).base64url(signature), see docs/ticket-format.md */
            qr: string;
            /** @example 3 */
            row: string;
            /** Format: uuid */
            seatId: string;
            /**
             * Format: int32
             * @example 5
             */
            seatNumber: number;
            /** @example A */
            section: string;
            /** @enum {string} */
            status: "ISSUED" | "CHECKED_IN" | "REVOKED";
            /** Format: uuid */
            ticketId: string;
            /** Format: int32 */
            version: number;
        };
        TokenResponse: {
            accessToken: string;
            /** Format: int64 */
            expiresIn: number;
            tokenType: string;
        };
        UserResponse: {
            email: string;
            /** Format: uuid */
            id: string;
            role: string;
        };
        VenueLayout: {
            /** Format: int32 */
            height: number;
            /** Format: uuid */
            id: string;
            name: string;
            /**
             * Format: int32
             * @description Radius of each seat circle, in venue units
             */
            seatRadius: number;
            sections: components["schemas"]["LayoutSection"][];
            /** @description Rectangle (top-left corner plus size) that shows which way the venue faces */
            stage: components["schemas"]["LayoutRect"];
            /** Format: int32 */
            width: number;
        };
        VenueRef: {
            /** Format: uuid */
            id: string;
            name: string;
        };
    };
    responses: never;
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    login: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["LoginRequest"];
            };
        };
        responses: {
            /** @description Logged in; the refresh token is set as an httpOnly cookie */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TokenResponse"];
                };
            };
            /** @description VALIDATION_FAILED */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description INVALID_CREDENTIALS */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description INTERNAL_ERROR */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    logout: {
        parameters: {
            query?: never;
            header?: {
                "X-Requested-With"?: string;
            };
            path?: never;
            cookie?: {
                stampede_refresh?: string;
            };
        };
        requestBody?: never;
        responses: {
            /** @description Logged out; the refresh cookie is cleared */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description FORBIDDEN */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description INTERNAL_ERROR */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    refresh: {
        parameters: {
            query?: never;
            header?: {
                "X-Requested-With"?: string;
            };
            path?: never;
            cookie?: {
                stampede_refresh?: string;
            };
        };
        requestBody?: never;
        responses: {
            /** @description Rotated; the new refresh token is set as an httpOnly cookie */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TokenResponse"];
                };
            };
            /** @description INVALID_REFRESH_TOKEN */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description FORBIDDEN */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description INTERNAL_ERROR */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    register: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["RegisterRequest"];
            };
        };
        responses: {
            /** @description Registered */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["UserResponse"];
                };
            };
            /** @description VALIDATION_FAILED */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description EMAIL_TAKEN */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description INTERNAL_ERROR */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    listEvents: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["EventList"];
                };
            };
            /** @description INTERNAL_ERROR */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getEvent: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["EventDetail"];
                };
            };
            /** @description VALIDATION_FAILED */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description EVENT_NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description INTERNAL_ERROR */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    hold: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["HoldSeatsRequest"];
            };
        };
        responses: {
            /** @description You already hold exactly these seats; that hold is returned */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["HoldResponse"];
                };
            };
            /** @description Held; Location is /holds/{holdId} */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["HoldResponse"];
                };
            };
            /** @description VALIDATION_FAILED */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description UNAUTHENTICATED */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description EVENT_NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description SEAT_TAKEN, SEAT_CONTENDED, LIMIT_EXCEEDED, SALE_NOT_OPEN */
            409: {
                headers: {
                    /** @description Sent with SEAT_CONTENDED: seconds to wait before trying again. */
                    "Retry-After"?: number;
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description INTERNAL_ERROR */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    eventLayout: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["EventLayout"];
                };
            };
            /** @description VALIDATION_FAILED */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description EVENT_NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description INTERNAL_ERROR */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    seatMap: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SeatMap"];
                };
            };
            /** @description VALIDATION_FAILED */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description EVENT_NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description INTERNAL_ERROR */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    cancel: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Released, or had already been released or expired */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description VALIDATION_FAILED */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description UNAUTHENTICATED */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description HOLD_NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description HOLD_CONVERTED */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description INTERNAL_ERROR */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    myTickets: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The caller's tickets */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MyTicketsResponse"];
                };
            };
            /** @description UNAUTHENTICATED */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description INTERNAL_ERROR */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    placeOrder: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PlaceOrderRequest"];
            };
        };
        responses: {
            /** @description This hold already has an order; that order is returned */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OrderResponse"];
                };
            };
            /** @description Ordered and paid; Location is /orders/{orderId} */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OrderResponse"];
                };
            };
            /** @description VALIDATION_FAILED */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description UNAUTHENTICATED */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description PAYMENT_FAILED */
            402: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description HOLD_NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description SEAT_TAKEN */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description HOLD_EXPIRED */
            410: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description INTERNAL_ERROR */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    getOrder: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The order */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OrderResponse"];
                };
            };
            /** @description VALIDATION_FAILED */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description UNAUTHENTICATED */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description ORDER_NOT_FOUND */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description INTERNAL_ERROR */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    ticketKeys: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The keys that sign tickets (JWKS, RFC 7517/8037) */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["KeySet"];
                };
            };
            /** @description INTERNAL_ERROR */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
}
