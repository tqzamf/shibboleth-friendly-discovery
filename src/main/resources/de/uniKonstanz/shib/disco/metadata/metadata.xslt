<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
		xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata"
		xmlns:mdui="urn:oasis:names:tc:SAML:metadata:ui"
		xmlns:idpdisco="urn:oasis:names:tc:SAML:profiles:SSO:idp-discovery-protocol">
	<xsl:output method="xml" encoding="UTF-8"/>

	<!-- root node: rename and keep only the <EntityDescriptor>s. -->
	<xsl:template match="/">
		<Metadata>
			<xsl:apply-templates select="md:EntitiesDescriptor/md:EntityDescriptor" />
		</Metadata>
	</xsl:template>
	<!-- SPs: extract entityID, and the list of response locations. -->
	<xsl:template match="/md:EntitiesDescriptor/md:EntityDescriptor[md:SPSSODescriptor]">
		<SP>
			<xsl:attribute name="entityID"><xsl:value-of select="@entityID" /></xsl:attribute>
			<!-- copy response locations having the right binding attribute -->
			<xsl:for-each select="md:SPSSODescriptor/md:Extensions/idpdisco:DiscoveryResponse
					[@Binding='urn:oasis:names:tc:SAML:profiles:SSO:idp-discovery-protocol']">
				<ReponseLocation>
					<xsl:attribute name="index"><xsl:value-of select="@index" /></xsl:attribute>
					<xsl:if test="@isDefault">
						<xsl:attribute name="isDefault"><xsl:value-of select="@isDefault" /></xsl:attribute>
					</xsl:if>
					<xsl:value-of select="@Location" />
				</ReponseLocation>
			</xsl:for-each>
		</SP>
	</xsl:template>
	<!-- SPs: extract entityID, display name and logos. -->
	<xsl:template match="/md:EntitiesDescriptor/md:EntityDescriptor[md:IDPSSODescriptor]">
		<IDP>
			<xsl:attribute name="entityID"><xsl:value-of select="@entityID" /></xsl:attribute>
			<!-- copy all declared display names -->
			<xsl:for-each select="md:IDPSSODescriptor/md:Extensions/mdui:UIInfo/mdui:DisplayName">
				<DisplayName>
					<xsl:attribute name="lang"><xsl:value-of select="@xml:lang" /></xsl:attribute>
					<xsl:value-of select="." />
				</DisplayName>
			</xsl:for-each>
			<!-- copy all declared logos -->
			<xsl:for-each select="md:IDPSSODescriptor/md:Extensions/mdui:UIInfo/mdui:Logo">
				<Logo>
					<xsl:attribute name="width"><xsl:value-of select="@width" /></xsl:attribute>
					<xsl:attribute name="height"><xsl:value-of select="@height" /></xsl:attribute>
					<xsl:value-of select="." />
				</Logo>
			</xsl:for-each>
		</IDP>
	</xsl:template>
	<!-- unmatched content nodes are ignored automatically; text nodes aren't.
	     ignore them explicitly here. -->
	<xsl:template match="text()" />
</xsl:stylesheet>
